import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  buildCatalogProductsUrl,
  catalogProductFields,
  incrementalUpdatedSince,
  parseCatalogSyncArgs,
} from "../scripts/lib/medusa-catalog-sync.mjs";
import { secureMedusaUrl } from "../scripts/lib/secure-medusa-url.mjs";

test("catalog sync defaults to an incremental window with bounded overlap", () => {
  const defaults = parseCatalogSyncArgs([], {});
  assert.equal(defaults.full, false);
  assert.equal(defaults.overlapSeconds, 300);
  assert.equal(defaults.markMissingInactive, false);

  const configured = parseCatalogSyncArgs([], { CATALOG_SYNC_OVERLAP_SECONDS: "900" });
  assert.equal(configured.overlapSeconds, 900);
  assert.throws(
    () => parseCatalogSyncArgs(["--offset", "10"], {}),
    /--offset requires --full/,
  );
  assert.throws(
    () => parseCatalogSyncArgs(["--overlap-seconds", "29"], {}),
    /must be at least 30/,
  );
  assert.throws(
    () => parseCatalogSyncArgs(["--overlap-seconds", "86401"], {}),
    /cannot exceed 86400/,
  );
});

test("legacy standalone inactive flag stays incremental and full reconcile remains explicit", () => {
  const scheduledLegacy = parseCatalogSyncArgs(
    ["--apply", "--page-size", "1000", "--mark-missing-inactive"],
    {},
  );
  assert.equal(scheduledLegacy.full, false);
  assert.equal(scheduledLegacy.markMissingInactive, false);
  assert.equal(scheduledLegacy.ignoredStandaloneMarkMissingInactive, true);

  const reconcile = parseCatalogSyncArgs(
    ["--apply", "--full", "--mark-missing-inactive"],
    {},
  );
  assert.equal(reconcile.full, true);
  assert.equal(reconcile.markMissingInactive, true);
  assert.throws(
    () => parseCatalogSyncArgs(["--full", "--mark-missing-inactive"], {}),
    /requires --apply/,
  );
});

test("checkpoint uses the last successful high watermark with an overlap window", () => {
  assert.equal(
    incrementalUpdatedSince(
      {
        source_high_watermark: "2026-07-27T12:00:00.000Z",
        started_at: "2026-07-27T11:00:00.000Z",
      },
      300,
    ),
    "2026-07-27T11:55:00.000Z",
  );
  assert.equal(
    incrementalUpdatedSince({ started_at: "2026-07-27T12:00:00.000Z" }, 60),
    "2026-07-27T11:59:00.000Z",
  );
  assert.equal(incrementalUpdatedSince(undefined, 300), null);
});

test("Medusa request uses updated_at gte and omits calculated prices", () => {
  const url = buildCatalogProductsUrl(new URL("https://medusa.example.test"), {
    limit: 500,
    offset: 0,
    salesChannel: "sc_1",
    region: "reg_1",
    updatedSince: "2026-07-27T11:55:00.000Z",
  });

  assert.equal(url.pathname, "/store/products");
  assert.equal(url.searchParams.get("updated_at[$gte]"), "2026-07-27T11:55:00.000Z");
  assert.equal(url.searchParams.get("order"), "updated_at");
  assert.equal(url.searchParams.get("sales_channel_id"), "sc_1");
  assert.equal(url.searchParams.get("region_id"), "reg_1");
  assert.equal(url.searchParams.get("fields"), catalogProductFields);
  assert.match(catalogProductFields, /variants\.id/);
  assert.match(catalogProductFields, /categories\.parent_category_id/);
  assert.match(catalogProductFields, /images\.url/);
  assert.doesNotMatch(catalogProductFields, /\*variants|\*categories|\*images|calculated_price/);
});

test("catalog synchronization rejects remote cleartext origins", () => {
  for (const value of [
    "http://medusa.example.kz",
    "http://78.140.246.238:9001",
    "http://78.140.246.238:9000/store",
  ]) {
    assert.throws(() => secureMedusaUrl(value), /MEDUSA_URL/, value);
  }
});

test("catalog synchronization permits only the exact legacy origin opt-in", () => {
  const previous = process.env.MEDUSA_ALLOW_INSECURE_LEGACY_HTTP;
  process.env.MEDUSA_ALLOW_INSECURE_LEGACY_HTTP = "true";
  try {
    assert.equal(
      secureMedusaUrl("http://78.140.246.238:9000").href,
      "http://78.140.246.238:9000/",
    );
    assert.throws(
      () => secureMedusaUrl("http://78.140.246.238:9000/store"),
      /MEDUSA_URL/,
    );
  } finally {
    if (previous === undefined) delete process.env.MEDUSA_ALLOW_INSECURE_LEGACY_HTTP;
    else process.env.MEDUSA_ALLOW_INSECURE_LEGACY_HTTP = previous;
  }
});

test("checkpoint advances only from a completed eligible run", async () => {
  const source = await readFile(
    new URL("../scripts/sync-medusa-catalog.mjs", import.meta.url),
    "utf8",
  );

  assert.match(source, /WHERE status = 'completed' AND source = \$1/);
  assert.match(source, /metrics @> '\{"checkpoint_eligible":true\}'::jsonb/);
  assert.match(source, /updatedSince = incrementalUpdatedSince/);
  assert.match(source, /source_updated_since: updatedSince/);
  assert.match(source, /checkpoint_eligible: isWindowComplete/);
  assert.match(source, /await completeRun[\s\S]+event: "run_completed"/);
  assert.match(source, /secureMedusaUrl\(requiredEnvironment\("MEDUSA_URL"\)\)/);
  assert.doesNotMatch(source, /productFields/);
});
