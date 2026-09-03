import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { resolveLocalFirstProduct } from "../src/lib/product-read.ts";

test("local PDP record is returned verbatim without a Medusa lookup", async () => {
  const events = [];
  const local = {
    id: "prod_local",
    description: undefined,
    price: 4_955,
    priceTBD: false,
    stockPharmacies: 12,
    image: "/api/media/medusa?path=%2Fstatic%2Fprimary.webp",
    images: [
      "/api/media/medusa?path=%2Fstatic%2Fprimary.webp",
      "/api/media/medusa?path=%2Fstatic%2Fback.webp",
    ],
  };

  const result = await resolveLocalFirstProduct("local-handle", {
    local: async (handle) => {
      events.push(`local:${handle}`);
      return local;
    },
    fallback: async (handle) => {
      events.push(`medusa:${handle}`);
      throw new Error("direct Medusa lookup must not run");
    },
  });

  assert.equal(result, local);
  assert.deepEqual(events, ["local:local-handle"]);
  assert.equal(result.price, 4_955);
  assert.equal(result.images.length, 2);
});

test("missing local handle falls back to Medusa after the local read", async () => {
  const events = [];
  const remote = { id: "prod_remote" };
  const result = await resolveLocalFirstProduct("remote-handle", {
    local: async () => {
      events.push("local");
      return null;
    },
    fallback: async () => {
      events.push("medusa");
      return remote;
    },
  });

  assert.equal(result, remote);
  assert.deepEqual(events, ["local", "medusa"]);
});

test("unavailable local catalogue falls back to Medusa after the failed read", async () => {
  const events = [];
  const remote = { id: "prod_remote" };
  const result = await resolveLocalFirstProduct("remote-handle", {
    local: async () => {
      events.push("local");
      throw new Error("postgres unavailable");
    },
    fallback: async () => {
      events.push("medusa");
      return remote;
    },
  });

  assert.equal(result, remote);
  assert.deepEqual(events, ["local", "medusa"]);
});

test("disabled local catalogue falls back to Medusa after the local probe", async () => {
  const events = [];
  await resolveLocalFirstProduct("remote-handle", {
    local: async () => {
      events.push("local");
      return undefined;
    },
    fallback: async () => {
      events.push("medusa");
      return null;
    },
  });

  assert.deepEqual(events, ["local", "medusa"]);
});

test("PDP page and product API share the local-first resolver", async () => {
  const [apiSource, routeSource] = await Promise.all([
    readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8"),
    readFile(new URL("../src/app/api/product/[slug]/route.ts", import.meta.url), "utf8"),
  ]);

  assert.match(apiSource, /resolveLocalFirstProduct\(slug,\s*\{\s*local:\s*getCatalogReadProduct,/s);
  assert.match(routeSource, /resolveProductBySlug\(slug\)/);
  assert.doesNotMatch(routeSource, /getMedusaProductByHandle\(slug\)/);
});
