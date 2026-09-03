#!/usr/bin/env node
import { createReadStream, createWriteStream } from "node:fs";
import { link, mkdir, open, unlink } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { createGunzip } from "node:zlib";
import { createInterface } from "node:readline";
import sharp from "sharp";
import { Pool } from "pg";
import {
  aurmaHandleScore,
  barcodeFromOpenFactsKey,
  canonicalHandleScore,
  distinctiveCanonicalHandleTokens,
  distinctiveHandleTokens,
  extractEuropharmaProductLinks,
  normalizeBarcode,
  openFactsImageScore,
  parseAptekaPlusProductHtml,
  parseEuropharmaProductHtml,
  selectCandidate,
  stableImageUuid,
  titleSimilarity,
} from "./lib/product-image-enrichment.mjs";

const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138.0 Safari/537.36 AptekaSoSkladaImageEnrichment/1.0";
const MAX_IMAGE_BYTES = 15 * 1024 * 1024;

function usage() {
  console.log(`
Usage:
  node scripts/enrich-missing-product-images.mjs inventory --catalog-url <url> --manifest <jsonl>
  node scripts/enrich-missing-product-images.mjs discover --inventory <jsonl> --manifest <jsonl> [--open-beauty-csv <csv.gz>] [--open-products-csv <csv.gz>] [--open-food-keys <data_keys.gz>]
  node scripts/enrich-missing-product-images.mjs discover-aurma --inventory <jsonl> --sitemap-dir <dir> --manifest <jsonl> [--concurrency 4] [--limit 100]
  node scripts/enrich-missing-product-images.mjs discover-europharma --inventory <jsonl> --manifest <jsonl> [--catalog-base https://oral.europharma.kz/ru] [--max-pages 250] [--concurrency 12]
  node scripts/enrich-missing-product-images.mjs discover-aptekaplus --inventory <jsonl> --sitemap-dir <dir> --manifest <jsonl> [--concurrency 16] [--limit 100]
  node scripts/enrich-missing-product-images.mjs discover-aptekaplus-search --inventory <jsonl> --manifest <jsonl> [--concurrency 32] [--limit 100]
  node scripts/enrich-missing-product-images.mjs verify --candidates <jsonl> --output-dir <dir> --manifest <jsonl> --limit 20 [--include-review]
  node scripts/enrich-missing-product-images.mjs apply --candidates <jsonl> --uploads-dir <dir> --manifest <jsonl> [--limit 5] [--apply --confirm-all]

Only products without media are inventoried. Discovery is exact-barcode only.
Apply requires DATABASE_URL and never replaces an existing thumbnail.
`);
}

function decodeXml(value) {
  return String(value || "")
    .replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">");
}

async function readAurmaSitemaps(directory) {
  const records = [];
  for (const suffix of ["", "_2", "_3"]) {
    const path = join(resolve(directory), `sitemap_images_product${suffix}.xml`);
    const xml = await import("node:fs/promises").then(({ readFile }) => readFile(path, "utf8"));
    for (const match of xml.matchAll(/<url>\s*<loc>([^<]+)<\/loc>[\s\S]*?<image:loc>([^<]+)<\/image:loc>[\s\S]*?<\/url>/g)) {
      const pageUrl = decodeXml(match[1]);
      const imageUrl = decodeXml(match[2]);
      const slug = new URL(pageUrl).pathname.split("/").filter(Boolean).at(-1) || "";
      records.push({ page_url: pageUrl, image_url: imageUrl, slug });
    }
  }
  return records;
}

function aurmaLocalProposals(products, records) {
  const productById = new Map(products.map((product) => [product.product_id, product]));
  const index = new Map();
  for (const product of products) {
    for (const token of distinctiveHandleTokens(product.handle)) {
      const ids = index.get(token) || [];
      ids.push(product.product_id);
      index.set(token, ids);
    }
  }
  const proposals = new Map();
  for (const record of records) {
    const tokenGroups = distinctiveHandleTokens(record.slug)
      .map((token) => index.get(token) || [])
      .filter((ids) => ids.length)
      .sort((left, right) => left.length - right.length);
    const possible = tokenGroups[0] || [];
    for (const id of possible) {
      const product = productById.get(id);
      const score = aurmaHandleScore(product.handle, record.slug);
      if (score < 0.68) continue;
      const values = proposals.get(id) || [];
      values.push({ ...record, score });
      values.sort((left, right) => right.score - left.score);
      proposals.set(id, values.slice(0, 3));
    }
  }
  return proposals;
}

async function fetchText(url, retries = 1) {
  let lastError;
  for (let attempt = 0; attempt < retries; attempt += 1) {
    try {
      const response = await fetch(url, {
        headers: { "User-Agent": USER_AGENT, Accept: "text/html" },
        signal: AbortSignal.timeout(12_000),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.text();
    } catch (error) {
      lastError = error;
      await new Promise((resolveWait) => setTimeout(resolveWait, 350 * (attempt + 1)));
    }
  }
  throw lastError;
}

function aurmaStructuredProduct(html) {
  for (const match of String(html).matchAll(/<script[^>]+type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi)) {
    try {
      const data = JSON.parse(match[1]);
      if (data?.["@type"] === "Product" && data.gtin && data.image) return data;
    } catch {}
  }
  return null;
}

async function discoverAurma(args) {
  if (!args.inventory || !args.sitemapDir || !args.manifest) {
    throw new Error("discover-aurma requires --inventory, --sitemap-dir and --manifest");
  }
  const products = (await readJsonl(args.inventory)).filter((product) => product.barcode);
  const records = await readAurmaSitemaps(args.sitemapDir);
  const proposals = aurmaLocalProposals(products, records);
  const queue = [...proposals.entries()];
  const maxProducts = args.limit === null ? queue.length : Math.min(args.limit, queue.length);
  const selected = queue.slice(0, maxProducts);
  const productById = new Map(products.map((product) => [product.product_id, product]));
  const matches = [];
  const writer = await jsonlWriter(args.manifest);
  let next = 0;
  let pagesChecked = 0;
  let processed = 0;
  async function worker() {
    while (next < selected.length) {
      const [productId, candidates] = selected[next++];
      const product = productById.get(productId);
      for (const candidate of candidates) {
        try {
          const data = aurmaStructuredProduct(await fetchText(candidate.page_url));
          pagesChecked += 1;
          if (!data || normalizeBarcode(data.gtin) !== product.barcode) continue;
          const matched = {
            event: "matched",
            source: "aurma",
            product_id: product.product_id,
            handle: product.handle,
            title: product.title,
            sku: product.sku,
            variant_id: product.variant_id,
            barcode: product.barcode,
            raw_barcode: product.raw_barcode,
            source_title: data.name || null,
            source_brand: data.brand?.name || data.brand || null,
            source_url: candidate.page_url,
            image_url: data.image,
            image_score: 100,
            handle_score: candidate.score,
            status: "eligible",
            license: "Source: Aurma product card; commercial image reuse rights must be confirmed by the catalog owner",
          };
          matches.push(matched);
          writer.write(matched);
          break;
        } catch {}
      }
      processed += 1;
      if (processed % 250 === 0) {
        console.log(JSON.stringify({ progress: processed, selected: selected.length, exact_barcode_matches: matches.length }));
      }
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, worker));
  await writer.close();
  console.log(JSON.stringify({
    missing_with_barcode: products.length,
    sitemap_records: records.length,
    local_proposals: proposals.size,
    selected_products: selected.length,
    pages_checked: pagesChecked,
    exact_barcode_matches: matches.length,
    manifest: resolve(args.manifest),
  }, null, 2));
}

async function discoverEuropharma(args) {
  if (!args.inventory || !args.manifest) {
    throw new Error("discover-europharma requires --inventory and --manifest");
  }
  const catalogBase = String(args.catalogBase || "https://oral.europharma.kz/ru").replace(/\/$/, "");
  const catalogUrl = new URL(catalogBase);
  if (catalogUrl.protocol !== "https:" || !catalogUrl.hostname.endsWith("europharma.kz")) {
    throw new Error("catalog-base must be an HTTPS europharma.kz URL");
  }
  const maxPages = Math.min(1_000, Math.max(1, Number.parseInt(args.maxPages || "250", 10) || 250));
  const products = (await readJsonl(args.inventory)).filter((product) => product.barcode);
  const byBarcode = new Map();
  for (const product of products) {
    const values = byBarcode.get(product.barcode) || [];
    values.push(product);
    byBarcode.set(product.barcode, values);
  }

  const pageNumbers = Array.from({ length: maxPages }, (_, index) => index + 1);
  const productUrls = new Set();
  let pageCursor = 0;
  let catalogPagesFetched = 0;
  async function catalogWorker() {
    while (pageCursor < pageNumbers.length) {
      const page = pageNumbers[pageCursor++];
      try {
        const html = await fetchText(`${catalogBase}/catalog?page=${page}`, 2);
        for (const url of extractEuropharmaProductLinks(html, catalogBase)) productUrls.add(url);
        catalogPagesFetched += 1;
      } catch {}
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, catalogWorker));

  const urls = [...productUrls];
  const writer = await jsonlWriter(args.manifest);
  const matches = new Map();
  let detailCursor = 0;
  let detailsFetched = 0;
  let detailsParsed = 0;
  async function detailWorker() {
    while (detailCursor < urls.length) {
      const sourceUrl = urls[detailCursor++];
      try {
        const data = parseEuropharmaProductHtml(await fetchText(sourceUrl, 2));
        detailsFetched += 1;
        if (!data) continue;
        detailsParsed += 1;
        const targets = byBarcode.get(data.barcode) || [];
        for (const product of targets) {
          if (matches.has(product.product_id)) continue;
          matches.set(product.product_id, {
            event: "matched",
            source: "europharma",
            product_id: product.product_id,
            handle: product.handle,
            title: product.title,
            sku: product.sku,
            variant_id: product.variant_id,
            barcode: product.barcode,
            raw_barcode: product.raw_barcode,
            source_title: data.name,
            source_brand: data.brand,
            source_sku: data.sku,
            source_barcode: data.raw_barcode,
            source_url: sourceUrl,
            image_url: data.image,
            image_score: 100,
            status: "eligible",
            license: "Source: Europharma product card; commercial image reuse rights must be confirmed by the catalog owner",
          });
        }
      } catch {}
      if (detailsFetched && detailsFetched % 250 === 0) {
        console.log(JSON.stringify({ progress: detailsFetched, product_pages: urls.length, exact_barcode_matches: matches.size }));
      }
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, detailWorker));
  for (const match of matches.values()) writer.write(match);
  await writer.close();
  console.log(JSON.stringify({
    missing_with_barcode: products.length,
    catalog_pages_requested: maxPages,
    catalog_pages_fetched: catalogPagesFetched,
    product_pages: urls.length,
    details_fetched: detailsFetched,
    details_parsed: detailsParsed,
    exact_barcode_matches: matches.size,
    manifest: resolve(args.manifest),
  }, null, 2));
}

async function readAptekaPlusSitemaps(directory) {
  const records = [];
  for (const suffix of ["1", "2"]) {
    const path = join(resolve(directory), `aptekaplus-products-${suffix}.xml`);
    const xml = await import("node:fs/promises").then(({ readFile }) => readFile(path, "utf8"));
    for (const match of xml.matchAll(/<loc>([^<]+)<\/loc>/g)) {
      const pageUrl = decodeXml(match[1]);
      try {
        const url = new URL(pageUrl);
        if (url.protocol !== "https:" || url.hostname !== "aptekaplus.kz" || !url.pathname.includes("/catalog/product/")) continue;
        records.push({
          page_url: url.toString(),
          slug: decodeURIComponent(url.pathname.split("/").filter(Boolean).at(-1) || ""),
        });
      } catch {}
    }
  }
  return records;
}

function aptekaPlusLocalProposals(products, records) {
  const productById = new Map(products.map((product) => [product.product_id, product]));
  const index = new Map();
  for (const product of products) {
    for (const token of distinctiveCanonicalHandleTokens(product.handle)) {
      const ids = index.get(token) || [];
      ids.push(product.product_id);
      index.set(token, ids);
    }
  }
  const proposals = new Map();
  for (const record of records) {
    const tokenGroups = distinctiveCanonicalHandleTokens(record.slug)
      .map((token) => index.get(token) || [])
      .filter((ids) => ids.length)
      .sort((left, right) => left.length - right.length);
    const possible = tokenGroups[0] || [];
    for (const id of possible) {
      const product = productById.get(id);
      const score = canonicalHandleScore(product.handle, record.slug);
      if (score < 0.55) continue;
      const values = proposals.get(id) || [];
      values.push({ ...record, score });
      values.sort((left, right) => right.score - left.score);
      proposals.set(id, values.slice(0, 5));
    }
  }
  return proposals;
}

async function discoverAptekaPlus(args) {
  if (!args.inventory || !args.sitemapDir || !args.manifest) {
    throw new Error("discover-aptekaplus requires --inventory, --sitemap-dir and --manifest");
  }
  const products = (await readJsonl(args.inventory)).filter((product) => product.barcode);
  const records = await readAptekaPlusSitemaps(args.sitemapDir);
  const proposals = aptekaPlusLocalProposals(products, records);
  const queue = [...proposals.entries()];
  const selected = args.limit === null ? queue : queue.slice(0, Math.min(args.limit, queue.length));
  const productById = new Map(products.map((product) => [product.product_id, product]));
  const matches = [];
  const writer = await jsonlWriter(args.manifest);
  let next = 0;
  let pagesChecked = 0;
  let pagesParsed = 0;
  let processed = 0;
  async function worker() {
    while (next < selected.length) {
      const [productId, candidates] = selected[next++];
      const product = productById.get(productId);
      for (const candidate of candidates) {
        try {
          const data = parseAptekaPlusProductHtml(await fetchText(candidate.page_url, 2));
          pagesChecked += 1;
          if (!data) continue;
          pagesParsed += 1;
          if (data.barcode !== product.barcode) continue;
          const matched = {
            event: "matched",
            source: "aptekaplus",
            product_id: product.product_id,
            handle: product.handle,
            title: product.title,
            sku: product.sku,
            variant_id: product.variant_id,
            barcode: product.barcode,
            raw_barcode: product.raw_barcode,
            source_title: data.name,
            source_barcode: data.raw_barcode,
            source_url: candidate.page_url,
            image_url: data.image,
            image_score: 100,
            handle_score: candidate.score,
            status: "eligible",
            license: "Source: AptekaPlus product card; commercial image reuse rights must be confirmed by the catalog owner",
          };
          matches.push(matched);
          writer.write(matched);
          break;
        } catch {}
      }
      processed += 1;
      if (processed % 250 === 0) {
        console.log(JSON.stringify({ progress: processed, selected: selected.length, pages_checked: pagesChecked, exact_barcode_matches: matches.length }));
      }
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, worker));
  await writer.close();
  console.log(JSON.stringify({
    missing_with_barcode: products.length,
    sitemap_records: records.length,
    local_proposals: proposals.size,
    selected_products: selected.length,
    pages_checked: pagesChecked,
    pages_parsed: pagesParsed,
    exact_barcode_matches: matches.length,
    manifest: resolve(args.manifest),
  }, null, 2));
}

async function discoverAptekaPlusSearch(args) {
  if (!args.inventory || !args.manifest) {
    throw new Error("discover-aptekaplus-search requires --inventory and --manifest");
  }
  const products = (await readJsonl(args.inventory)).filter((product) => product.barcode);
  const selected = args.limit === null ? products : products.slice(0, Math.min(args.limit, products.length));
  const writer = await jsonlWriter(args.manifest);
  let cursor = 0;
  let checked = 0;
  let exactMatches = 0;
  let failed = 0;
  async function worker() {
    while (cursor < selected.length) {
      const product = selected[cursor++];
      const queries = [...new Set([product.raw_barcode, product.barcode].filter(Boolean))];
      let matched = null;
      for (const query of queries) {
        try {
          const url = new URL("https://aptekaplus.kz/api/catalog/v1/search");
          url.searchParams.set("q", query);
          url.searchParams.set("ol", "1");
          const data = await fetchJson(url, 2);
          matched = (data?.products || []).find((item) =>
            normalizeBarcode(item?.barcode) === product.barcode
              && /^https:\/\/bitrix\.aptekaplus\.kz\/cdn\/\?q=/i.test(String(item?.image || "")),
          ) || null;
          if (matched) break;
        } catch {
          failed += 1;
        }
      }
      if (matched) {
        exactMatches += 1;
        writer.write({
          event: "matched",
          source: "aptekaplus",
          product_id: product.product_id,
          handle: product.handle,
          title: product.title,
          sku: product.sku,
          variant_id: product.variant_id,
          barcode: product.barcode,
          raw_barcode: product.raw_barcode,
          source_title: matched.name || null,
          source_barcode: String(matched.barcode || "").replace(/\D/g, ""),
          source_url: `https://aptekaplus.kz/catalog/product/${encodeURIComponent(matched.slug)}`,
          image_url: matched.image,
          image_score: 100,
          status: "eligible",
          license: "Source: AptekaPlus product card; commercial image reuse rights must be confirmed by the catalog owner",
        });
      }
      checked += 1;
      if (checked % 250 === 0) {
        console.log(JSON.stringify({ progress: checked, selected: selected.length, exact_barcode_matches: exactMatches, request_failures: failed }));
      }
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, worker));
  await writer.close();
  console.log(JSON.stringify({
    missing_with_barcode: products.length,
    selected_products: selected.length,
    checked,
    exact_barcode_matches: exactMatches,
    request_failures: failed,
    manifest: resolve(args.manifest),
  }, null, 2));
}

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const args = { command, apply: false, confirmAll: false, includeReview: false, limit: null, concurrency: 4 };
  for (let index = 0; index < rest.length; index += 1) {
    const key = rest[index];
    if (key === "--apply") args.apply = true;
    else if (key === "--confirm-all") args.confirmAll = true;
    else if (key === "--include-review") args.includeReview = true;
    else if (key.startsWith("--")) {
      const value = rest[++index];
      if (!value || value.startsWith("--")) throw new Error(`${key} requires a value`);
      const name = key.slice(2).replace(/-([a-z])/g, (_, char) => char.toUpperCase());
      args[name] = value;
    }
  }
  if (args.limit !== null) args.limit = Math.max(1, Number.parseInt(args.limit, 10));
  args.concurrency = Math.min(32, Math.max(1, Number.parseInt(args.concurrency, 10) || 4));
  return args;
}

async function jsonlWriter(path) {
  await mkdir(dirname(resolve(path)), { recursive: true });
  const stream = createWriteStream(resolve(path), { flags: "w", encoding: "utf8" });
  return {
    write(value) { stream.write(`${JSON.stringify(value)}\n`); },
    async close() { stream.end(); await new Promise((resolveClose, reject) => stream.on("finish", resolveClose).on("error", reject)); },
  };
}

async function readJsonl(path) {
  const values = [];
  const lines = createInterface({ input: createReadStream(resolve(path), { encoding: "utf8" }), crlfDelay: Infinity });
  for await (const line of lines) if (line.trim()) values.push(JSON.parse(line));
  return values;
}

async function fetchJson(url, retries = 3) {
  let lastError;
  for (let attempt = 0; attempt < retries; attempt += 1) {
    try {
      const response = await fetch(url, { headers: { "User-Agent": USER_AGENT, Accept: "application/json" }, signal: AbortSignal.timeout(20_000) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.json();
    } catch (error) {
      lastError = error;
      await new Promise((resolveWait) => setTimeout(resolveWait, 300 * (attempt + 1)));
    }
  }
  throw lastError;
}

async function inventory(args) {
  if (!args.catalogUrl || !args.manifest) throw new Error("inventory requires --catalog-url and --manifest");
  const base = new URL(args.catalogUrl);
  const pageUrl = (page) => {
    const url = new URL(base);
    url.searchParams.set("limit", "100");
    url.searchParams.set("page", String(page));
    url.searchParams.set("includeFacets", "0");
    return url;
  };
  const first = await fetchJson(pageUrl(1));
  const totalPages = Number(first?.pagination?.totalPages || 1);
  const products = [...(first.products || [])];
  let nextPage = 2;
  async function worker() {
    while (nextPage <= totalPages) {
      const page = nextPage++;
      const data = await fetchJson(pageUrl(page));
      products.push(...(data.products || []));
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, worker));
  const writer = await jsonlWriter(args.manifest);
  const barcodeOwners = new Map();
  let missing = 0;
  let missingWithBarcode = 0;
  for (const product of products) {
    if (product.image || (Array.isArray(product.images) && product.images.length)) continue;
    missing += 1;
    const variants = Array.isArray(product.variants) ? product.variants : [];
    const rawBarcode = product.barcode || variants.find((variant) => variant?.barcode)?.barcode || null;
    const barcode = normalizeBarcode(rawBarcode);
    if (barcode) missingWithBarcode += 1;
    if (barcode) barcodeOwners.set(barcode, (barcodeOwners.get(barcode) || 0) + 1);
    writer.write({
      product_id: product.id,
      handle: product.slug,
      title: product.name,
      brand: product.brand || null,
      manufacturer: product.manufacturer || null,
      barcode,
      raw_barcode: rawBarcode ? String(rawBarcode).replace(/\D/g, "") : null,
      variant_id: product.variantId || variants[0]?.id || null,
      sku: variants[0]?.sku || null,
    });
  }
  await writer.close();
  const duplicateBarcodeGroups = [...barcodeOwners.values()].filter((count) => count > 1).length;
  console.log(JSON.stringify({ total: products.length, with_image: products.length - missing, missing, missing_with_barcode: missingWithBarcode, missing_without_barcode: missing - missingWithBarcode, duplicate_barcode_groups: duplicateBarcodeGroups, manifest: resolve(args.manifest) }, null, 2));
}

function openMaybeGzip(path) {
  const input = createReadStream(resolve(path));
  return String(path).toLowerCase().endsWith(".gz") ? input.pipe(createGunzip()) : input;
}

function addCandidate(candidates, byBarcode, candidate) {
  const products = byBarcode.get(candidate.barcode) || [];
  for (const product of products) {
    const similarity = titleSimilarity(product.title, candidate.source_title);
    const status = Number(candidate.image_score || 0) < 85 ? "review_image_role" : "eligible";
    const enriched = {
      ...candidate,
      product_id: product.product_id,
      handle: product.handle,
      title: product.title,
      sku: product.sku,
      variant_id: product.variant_id,
      raw_barcode: product.raw_barcode,
      title_similarity: similarity,
      title_warning: similarity !== null && similarity < 0.12 ? "low_cross_language_similarity" : null,
      status,
    };
    candidates.set(product.product_id, selectCandidate(candidates.get(product.product_id), enriched));
  }
}

async function discoverCsv(path, source, byBarcode, candidates) {
  const lines = createInterface({ input: openMaybeGzip(path), crlfDelay: Infinity });
  let headers = null;
  for await (const line of lines) {
    const fields = line.split("\t");
    if (!headers) { headers = fields; continue; }
    const row = Object.fromEntries(headers.map((header, index) => [header, fields[index] || ""]));
    const barcode = normalizeBarcode(row.code);
    if (!barcode || !byBarcode.has(barcode)) continue;
    const imageUrl = row.image_front_url || row.image_url || row.image_front_small_url || row.image_small_url;
    if (!/^https:\/\//i.test(imageUrl)) continue;
    addCandidate(candidates, byBarcode, {
      source,
      barcode,
      source_title: row.product_name || row.product_name_en || row.generic_name || null,
      source_brand: row.brands || null,
      source_url: `https://world.${source}.org/product/${barcode}`,
      image_url: imageUrl,
      // Open Facts exports define image_url as the selected front image for
      // the product's current language; it is not an arbitrary gallery item.
      image_score: row.image_front_url || row.image_url ? 100 : 70,
      license: "Open Facts product image (CC BY-SA; attribution/share-alike required)",
    });
  }
}

async function discoverFoodKeys(path, byBarcode, candidates) {
  const best = new Map();
  const lines = createInterface({ input: openMaybeGzip(path), crlfDelay: Infinity });
  for await (const line of lines) {
    const key = line.trim();
    const barcode = barcodeFromOpenFactsKey(key);
    if (!barcode || !byBarcode.has(barcode)) continue;
    const score = openFactsImageScore(key);
    if (score < 0 || score <= (best.get(barcode)?.score ?? -1)) continue;
    best.set(barcode, { key, score });
  }
  for (const [barcode, value] of best) {
    addCandidate(candidates, byBarcode, {
      source: "openfoodfacts",
      barcode,
      source_title: null,
      source_brand: null,
      source_url: `https://world.openfoodfacts.org/product/${barcode}`,
      image_url: `https://openfoodfacts-images.s3.eu-west-3.amazonaws.com/${value.key}`,
      image_score: value.score,
      license: "Open Food Facts product image (CC BY-SA; attribution/share-alike required)",
    });
  }
}

async function discover(args) {
  if (!args.inventory || !args.manifest) throw new Error("discover requires --inventory and --manifest");
  const products = await readJsonl(args.inventory);
  const byBarcode = new Map();
  for (const product of products) {
    if (!product.barcode) continue;
    const group = byBarcode.get(product.barcode) || [];
    group.push(product);
    byBarcode.set(product.barcode, group);
  }
  const candidates = new Map();
  if (args.openBeautyCsv) await discoverCsv(args.openBeautyCsv, "openbeautyfacts", byBarcode, candidates);
  if (args.openProductsCsv) await discoverCsv(args.openProductsCsv, "openproductsfacts", byBarcode, candidates);
  if (args.openFoodKeys) await discoverFoodKeys(args.openFoodKeys, byBarcode, candidates);
  const writer = await jsonlWriter(args.manifest);
  let eligible = 0;
  let review = 0;
  for (const candidate of [...candidates.values()].sort((a, b) => a.product_id.localeCompare(b.product_id))) {
    writer.write(candidate);
    if (candidate.status === "eligible") eligible += 1;
    else review += 1;
  }
  await writer.close();
  console.log(JSON.stringify({ missing_products: products.length, exact_barcode_candidates: candidates.size, eligible, review, unmatched: products.length - candidates.size, manifest: resolve(args.manifest) }, null, 2));
}

function allowedImageUrl(value, extraHosts = []) {
  const url = new URL(value);
  const allowed = new Set([
    "images.openfoodfacts.org",
    "openfoodfacts-images.s3.eu-west-3.amazonaws.com",
    "images.openbeautyfacts.org",
    "images.openproductsfacts.org",
    "images.aurma.kz",
    "bitrix.aptekaplus.kz",
    ...extraHosts,
  ]);
  if (url.protocol !== "https:" || url.username || url.password || !allowed.has(url.hostname.toLowerCase())) throw new Error("image_host_not_allowed");
  return url;
}

async function downloadImage(url, extraHosts) {
  let current = allowedImageUrl(url, extraHosts);
  for (let redirect = 0; redirect <= 3; redirect += 1) {
    const response = await fetch(current, { redirect: "manual", headers: { "User-Agent": USER_AGENT, Accept: "image/*" }, signal: AbortSignal.timeout(20_000) });
    if (response.status >= 300 && response.status < 400 && response.headers.get("location")) {
      current = allowedImageUrl(new URL(response.headers.get("location"), current).toString(), extraHosts);
      continue;
    }
    if (!response.ok) throw new Error(`image_http_${response.status}`);
    const declared = Number(response.headers.get("content-length") || 0);
    if (declared > MAX_IMAGE_BYTES) throw new Error("image_too_large");
    const bytes = Buffer.from(await response.arrayBuffer());
    if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) throw new Error("invalid_image_size");
    return bytes;
  }
  throw new Error("too_many_redirects");
}

async function convertImage(bytes) {
  const input = sharp(bytes, { failOn: "error", limitInputPixels: 50_000_000 }).rotate();
  const metadata = await input.metadata();
  if (!metadata.width || !metadata.height || metadata.width < 160 || metadata.height < 160) throw new Error("image_dimensions_too_small");
  return input.flatten({ background: "#ffffff" }).resize({ width: 1200, height: 1200, fit: "inside", withoutEnlargement: true }).webp({ quality: 84, effort: 5 }).toBuffer();
}

async function verify(args) {
  if (!args.candidates || !args.outputDir || !args.manifest || args.limit === null) {
    throw new Error("verify requires --candidates, --output-dir, --manifest and --limit");
  }
  const extraHosts = String(args.allowedHosts || "").split(",").map((value) => value.trim().toLowerCase()).filter(Boolean);
  const candidates = (await readJsonl(args.candidates))
    .filter((candidate) => candidate.status === "eligible" || args.includeReview)
    .slice(0, Math.min(args.limit, 20_000));
  await mkdir(resolve(args.outputDir), { recursive: true });
  const writer = await jsonlWriter(args.manifest);
  let verified = 0;
  let failed = 0;
  let cursor = 0;
  async function worker() {
    while (cursor < candidates.length) {
      const candidate = candidates[cursor++];
      try {
        const bytes = await downloadImage(candidate.image_url, extraHosts);
        const webp = await convertImage(bytes);
        const filename = `${stableImageUuid(candidate.product_id, candidate.sku)}.webp`;
        const target = join(resolve(args.outputDir), filename);
        const file = await open(target, "w", 0o644);
        await file.writeFile(webp);
        await file.close();
        verified += 1;
        writer.write({ ...candidate, event: "verified", filename });
      } catch (error) {
        failed += 1;
        writer.write({ ...candidate, event: "failed", product_id: candidate.product_id, reason: error.message });
      }
      if ((verified + failed) % 250 === 0) {
        console.log(JSON.stringify({ progress: verified + failed, selected: candidates.length, verified, failed }));
      }
    }
  }
  await Promise.all(Array.from({ length: args.concurrency }, worker));
  await writer.close();
  console.log(JSON.stringify({ selected: candidates.length, verified, failed, output_dir: resolve(args.outputDir), manifest: resolve(args.manifest) }, null, 2));
  if (failed) process.exitCode = 2;
}

async function apply(args) {
  if (!args.candidates || !args.uploadsDir || !args.manifest) throw new Error("apply requires --candidates, --uploads-dir and --manifest");
  if (args.apply && !args.confirmAll && args.limit === null) throw new Error("full apply requires --confirm-all");
  if (args.apply && !process.env.DATABASE_URL) throw new Error("DATABASE_URL is required for apply");
  const extraHosts = String(args.allowedHosts || "").split(",").map((value) => value.trim().toLowerCase()).filter(Boolean);
  const candidates = (await readJsonl(args.candidates)).filter((candidate) => candidate.status === "eligible");
  const selected = args.limit === null ? candidates : candidates.slice(0, args.limit);
  const writer = await jsonlWriter(args.manifest);
  if (!args.apply) {
    for (const candidate of selected) writer.write({ event: "dry_run", ...candidate });
    await writer.close();
    console.log(JSON.stringify({ mode: "dry-run", eligible: candidates.length, selected: selected.length, manifest: resolve(args.manifest) }, null, 2));
    return;
  }
  await mkdir(resolve(args.uploadsDir), { recursive: true });
  const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 2, application_name: "inkar-image-enrichment" });
  let applied = 0;
  let skipped = 0;
  let failed = 0;
  for (const candidate of selected) {
    const filename = `${stableImageUuid(candidate.product_id, candidate.sku)}.webp`;
    const target = join(resolve(args.uploadsDir), filename);
    const temporary = `${target}.tmp-${process.pid}`;
    let wroteFile = false;
    try {
      const check = await pool.query(`
        SELECT p.thumbnail_url,
        EXISTS (SELECT 1 FROM catalog_images i WHERE i.product_id = p.id) AS has_image,
        EXISTS (
          SELECT 1 FROM catalog_variants v
          WHERE v.product_id = p.id AND v.active AND regexp_replace(coalesce(v.barcode, ''), '[^0-9]', '', 'g') = $2
        ) AS barcode_matches
        FROM catalog_products p WHERE p.id = $1 AND p.active
      `, [candidate.product_id, candidate.raw_barcode]);
      const row = check.rows[0];
      if (!row || row.thumbnail_url || row.has_image || !row.barcode_matches) {
        skipped += 1;
        writer.write({ event: "skipped", product_id: candidate.product_id, reason: !row ? "product_missing" : row.thumbnail_url || row.has_image ? "existing_media" : "barcode_mismatch" });
        continue;
      }
      const bytes = await downloadImage(candidate.image_url, extraHosts);
      const webp = await convertImage(bytes);
      try {
        const file = await open(temporary, "wx", 0o644);
        await file.writeFile(webp);
        await file.sync();
        await file.close();
        await link(temporary, target);
        await unlink(temporary);
        wroteFile = true;
      } catch (error) {
        await unlink(temporary).catch(() => {});
        if (error.code === "EEXIST") throw new Error("image_file_already_exists");
        throw error;
      }
      const url = `/api/uploads/${filename}`;
      const client = await pool.connect();
      try {
        await client.query("BEGIN");
        const updated = await client.query(`
          UPDATE catalog_products SET thumbnail_url = $2, updated_at = now()
          WHERE id = $1 AND active AND thumbnail_url IS NULL
            AND NOT EXISTS (SELECT 1 FROM catalog_images i WHERE i.product_id = catalog_products.id)
          RETURNING id
        `, [candidate.product_id, url]);
        if (!updated.rowCount) throw new Error("concurrent_existing_media");
        await client.query(`
          INSERT INTO catalog_images (id, product_id, url, position, metadata, source_seen_at, created_at, updated_at)
          VALUES ($1, $2, $3, 0, $4::jsonb, now(), now(), now())
          ON CONFLICT (product_id, url) DO UPDATE SET metadata = excluded.metadata, updated_at = now()
        `, [`internet_${stableImageUuid(candidate.product_id, candidate.sku)}`, candidate.product_id, url, JSON.stringify({ source: candidate.source, source_url: candidate.source_url, image_url: candidate.image_url, barcode: candidate.raw_barcode, license: candidate.license })]);
        await client.query("COMMIT");
      } catch (error) {
        await client.query("ROLLBACK");
        throw error;
      } finally {
        client.release();
      }
      applied += 1;
      writer.write({ event: "applied", product_id: candidate.product_id, handle: candidate.handle, barcode: candidate.raw_barcode, url, source: candidate.source, source_url: candidate.source_url });
    } catch (error) {
      failed += 1;
      if (wroteFile) await unlink(target).catch(() => {});
      await unlink(temporary).catch(() => {});
      writer.write({ event: "failed", product_id: candidate.product_id, reason: error.message });
    }
  }
  await pool.end();
  await writer.close();
  console.log(JSON.stringify({ mode: "apply", selected: selected.length, applied, skipped, failed, manifest: resolve(args.manifest) }, null, 2));
  if (failed) process.exitCode = 2;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.command || args.command === "help") return usage();
  if (args.command === "inventory") return inventory(args);
  if (args.command === "discover") return discover(args);
  if (args.command === "discover-aurma") return discoverAurma(args);
  if (args.command === "discover-europharma") return discoverEuropharma(args);
  if (args.command === "discover-aptekaplus") return discoverAptekaPlus(args);
  if (args.command === "discover-aptekaplus-search") return discoverAptekaPlusSearch(args);
  if (args.command === "verify") return verify(args);
  if (args.command === "apply") return apply(args);
  throw new Error(`unknown command: ${args.command}`);
}

main().catch((error) => { console.error(`[image-enrichment] ${error.message}`); process.exitCode = 1; });
