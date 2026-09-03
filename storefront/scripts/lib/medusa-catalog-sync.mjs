const DEFAULT_OVERLAP_SECONDS = 5 * 60;
const MINIMUM_OVERLAP_SECONDS = 30;
const MAXIMUM_OVERLAP_SECONDS = 24 * 60 * 60;

export const catalogProductFields = [
  "id",
  "title",
  "handle",
  "subtitle",
  "description",
  "thumbnail",
  "created_at",
  "updated_at",
  "metadata",
  "variants.id",
  "variants.title",
  "variants.sku",
  "variants.barcode",
  "variants.metadata",
  "categories.id",
  "categories.handle",
  "categories.name",
  "categories.parent_category_id",
  "categories.rank",
  "categories.metadata",
  "categories.updated_at",
  "images.id",
  "images.url",
  "images.metadata",
].join(",");

function parsePositiveInteger(raw, name, { allowZero = false } = {}) {
  if (!/^\d+$/.test(String(raw))) {
    throw new Error(`${name} must be an integer`);
  }
  const value = Number(raw);
  const minimum = allowZero ? 0 : 1;
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`${name} must be an integer >= ${minimum}`);
  }
  return value;
}

export function parseCatalogSyncArgs(argv, env = process.env) {
  const args = {
    apply: false,
    full: false,
    help: false,
    pageSize: 500,
    limit: null,
    offset: 0,
    requestedMarkMissingInactive: false,
    overlapSeconds: parsePositiveInteger(
      String(env.CATALOG_SYNC_OVERLAP_SECONDS || DEFAULT_OVERLAP_SECONDS),
      "CATALOG_SYNC_OVERLAP_SECONDS",
    ),
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--apply") args.apply = true;
    else if (argument === "--full") args.full = true;
    else if (argument === "--mark-missing-inactive") args.requestedMarkMissingInactive = true;
    else if (argument === "--help" || argument === "-h") args.help = true;
    else if (["--page-size", "--limit", "--offset", "--overlap-seconds"].includes(argument)) {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) throw new Error(`${argument} requires a value`);
      index += 1;
      if (argument === "--page-size") args.pageSize = parsePositiveInteger(value, argument);
      if (argument === "--limit") args.limit = parsePositiveInteger(value, argument);
      if (argument === "--offset") args.offset = parsePositiveInteger(value, argument, { allowZero: true });
      if (argument === "--overlap-seconds") {
        args.overlapSeconds = parsePositiveInteger(value, argument);
      }
    } else throw new Error(`Unknown argument: ${argument}`);
  }
  if (args.pageSize > 1_000) throw new Error("--page-size cannot exceed 1000");
  if (args.overlapSeconds < MINIMUM_OVERLAP_SECONDS) {
    throw new Error(`--overlap-seconds must be at least ${MINIMUM_OVERLAP_SECONDS}`);
  }
  if (args.overlapSeconds > MAXIMUM_OVERLAP_SECONDS) {
    throw new Error(`--overlap-seconds cannot exceed ${MAXIMUM_OVERLAP_SECONDS}`);
  }
  if (!args.full && args.offset !== 0) {
    throw new Error("--offset requires --full; incremental checkpoints always start at offset 0");
  }
  if (args.full && args.requestedMarkMissingInactive
      && (!args.apply || args.offset !== 0 || args.limit !== null)) {
    throw new Error("--mark-missing-inactive with --full requires --apply, offset 0 and no --limit");
  }
  return {
    ...args,
    // Compatibility for the existing hourly unit: a legacy standalone
    // --mark-missing-inactive must not silently turn the default delta run
    // back into a 27k-product reconcile. It is effective only with --full.
    markMissingInactive: args.full && args.requestedMarkMissingInactive,
    ignoredStandaloneMarkMissingInactive: !args.full && args.requestedMarkMissingInactive,
  };
}

function timestamp(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? null : date;
}

export function incrementalUpdatedSince(run, overlapSeconds) {
  const checkpoint = timestamp(run?.source_high_watermark) || timestamp(run?.started_at);
  if (!checkpoint) return null;
  const overlapMs = Math.max(0, Number(overlapSeconds) || 0) * 1_000;
  return new Date(Math.max(0, checkpoint.valueOf() - overlapMs)).toISOString();
}

export function buildCatalogProductsUrl(medusaUrl, {
  limit,
  offset,
  salesChannel,
  region,
  updatedSince,
}) {
  const url = new URL("/store/products", medusaUrl);
  url.searchParams.set("limit", String(limit));
  url.searchParams.set("offset", String(offset));
  // This Medusa deployment returns HTTP 500 for comma-separated order fields.
  // Offset pagination remains bounded and duplicate IDs are rejected by the
  // worker, so use the supported single timestamp order.
  url.searchParams.set("order", "updated_at");
  url.searchParams.set("fields", catalogProductFields);
  if (salesChannel) url.searchParams.set("sales_channel_id", salesChannel);
  if (region) url.searchParams.set("region_id", region);
  if (updatedSince) url.searchParams.set("updated_at[$gte]", updatedSince);
  return url;
}
