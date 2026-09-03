import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { verifiedSnapshotExpected } from "../src/lib/catalog-local-read.ts";

test("storefront verifies the total carried by an eligible incremental run", () => {
  assert.equal(verifiedSnapshotExpected({
    status: "completed",
    expected_products: 12,
    processed_products: 12,
    metrics: {
      scope: "incremental",
      checkpoint_eligible: true,
      catalog_total: 27_981,
      window_complete: true,
    },
  }), 27_981);
  assert.equal(verifiedSnapshotExpected({
    status: "completed",
    expected_products: 12,
    processed_products: 12,
    metrics: {
      scope: "incremental",
      checkpoint_eligible: false,
      catalog_total: 27_981,
      window_complete: false,
    },
  }), null);
});

test("both storefront snapshot readers ignore running and incomplete runs", async () => {
  const [localRead, snapshotCache] = await Promise.all([
    readFile(new URL("../src/lib/catalog-local-read.ts", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/catalog-db.ts", import.meta.url), "utf8"),
  ]);

  for (const source of [localRead, snapshotCache]) {
    assert.match(source, /WHERE status = 'completed'/);
    assert.match(source, /metrics @> '\{"checkpoint_eligible":true\}'::jsonb/);
    assert.match(source, /jsonb_typeof\(metrics->'catalog_total'\) = 'number'/);
    assert.match(source, /finished_at DESC NULLS LAST/);
  }
});
