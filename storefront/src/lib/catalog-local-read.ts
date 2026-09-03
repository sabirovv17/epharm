import type { CatNode, Product, ProductArtKind } from "@/lib/types";
import type { CatalogBrandFacet, CatalogFacets, CatalogQuery } from "@/lib/catalog-query";
import { medusaMediaUrl } from "./media-url.ts";

type CategoryItem = { id?: unknown; handle?: unknown; is_primary?: unknown };
type ImageItem = { url?: unknown };
type VariantItem = {
  id?: unknown;
  title?: unknown;
  sku?: unknown;
  barcode?: unknown;
  price?: unknown;
  original_price?: unknown;
};
type ProductRow = {
  id: string;
  handle: string;
  title: string;
  description: string | null;
  thumbnail_url: string | null;
  brand: string | null;
  manufacturer: string | null;
  country: string | null;
  mnn: string | null;
  atc: string | null;
  rx_otc: string | null;
  min_price: number | string | null;
  stock_pharmacy_count: number | string | null;
  categories: CategoryItem[] | null;
  images: ImageItem[] | null;
  variants: VariantItem[] | null;
};
type RunRow = {
  status: string;
  expected_products: unknown;
  processed_products: unknown;
  metrics: Record<string, unknown> | null;
};
type CategoryRow = { id: string; handle: string | null; name: string; parent_id: string | null; rank: number };
type FacetAggregateRow = {
  count: unknown;
  brands: unknown;
  categories: unknown;
  min_price: unknown;
  max_price: unknown;
  known_price_count: unknown;
  in_stock_count: unknown;
  sale_count: unknown;
  rx_count: unknown;
};
type LocalPool = import("pg").Pool;
type LocalClient = import("pg").PoolClient;

const root = globalThis as typeof globalThis & { __inkarStorefrontReadPool?: Promise<LocalPool> };
const PRODUCT_COLUMNS = `
  id, handle, title, description, thumbnail_url, brand, manufacturer, country,
  mnn, atc, rx_otc, min_price, stock_pharmacy_count, categories, images, variants
`;

function text(value: unknown): string | undefined {
  if (value == null) return undefined;
  const normalized = String(value).trim();
  return normalized || undefined;
}

function price(value: unknown): number | null {
  const normalized = Number(value);
  return Number.isFinite(normalized) && normalized > 0 ? Math.round(normalized) : null;
}

function art(title: string): { kind: ProductArtKind; hue: number } {
  const kinds: ProductArtKind[] = ["bottle", "tube", "jar", "box", "dropper", "spray"];
  let hash = 0;
  for (let index = 0; index < title.length; index += 1) hash = (hash * 31 + title.charCodeAt(index)) >>> 0;
  return { kind: kinds[hash % kinds.length], hue: hash % 360 };
}

function volume(title: string): string | undefined {
  return title.match(/\b\d+(?:[.,]\d+)?\s*(?:мл|мг|г|шт|капс\.?|таб\.?)\b/i)?.[0];
}

function mapRow(row: ProductRow, includeGallery = false): Product {
  const categories = Array.isArray(row.categories) ? row.categories : [];
  const primary = categories.find((category) => category.is_primary === true) || categories[0];
  const handles = categories.map((category) => text(category.handle)).filter(Boolean) as string[];
  const imageUrls = [...new Set([
    medusaMediaUrl(row.thumbnail_url),
    ...(Array.isArray(row.images) ? row.images.map((image) => medusaMediaUrl(image.url)) : []),
  ].filter(Boolean) as string[])];
  const variants = (Array.isArray(row.variants) ? row.variants : []).flatMap((variant) => {
    const id = text(variant.id);
    if (!id) return [];
    return [{
      id,
      title: text(variant.title) || row.title,
      sku: text(variant.sku),
      barcode: text(variant.barcode),
      price: price(variant.price) ?? undefined,
      originalPrice: price(variant.original_price),
    }];
  });
  const currentPrice = price(row.min_price);
  const pricedVariant = variants.find((variant) => variant.price === currentPrice);
  const oldPrice = pricedVariant?.originalPrice && currentPrice && pricedVariant.originalPrice > currentPrice
    ? pricedVariant.originalPrice
    : undefined;
  const rx = text(row.rx_otc)?.toLowerCase() === "rx";
  return {
    id: row.id,
    slug: row.handle,
    name: row.title,
    brand: text(row.brand) || "—",
    categorySlug: text(primary?.handle) || "",
    categoryHandles: handles,
    price: currentPrice ?? 0,
    oldPrice,
    priceTBD: currentPrice === null,
    rating: 0,
    reviews: 0,
    volume: volume(row.title),
    badges: rx ? ["rx"] : [],
    art: art(row.title),
    image: imageUrls[0],
    images: imageUrls.length ? (includeGallery ? imageUrls : imageUrls.slice(0, 1)) : undefined,
    description: text(row.description),
    inStock: currentPrice !== null,
    stockPharmacies: integer(Number(row.stock_pharmacy_count)) ?? 0,
    prescription: rx,
    manufacturer: text(row.manufacturer),
    country: text(row.country),
    mnn: text(row.mnn),
    atc: text(row.atc),
    barcode: variants[0]?.barcode,
    variantId: variants[0]?.id,
    variants: variants.length
      ? variants.map((variant) => ({
          id: variant.id,
          title: variant.title,
          sku: variant.sku,
          barcode: variant.barcode,
          price: variant.price,
        }))
      : undefined,
  };
}

async function pool(): Promise<LocalPool> {
  const connectionString = process.env.DATABASE_URL || process.env.POSTGRES_URL || "";
  if (!connectionString) throw new Error("catalog_database_not_configured");
  if (root.__inkarStorefrontReadPool) return root.__inkarStorefrontReadPool;
  const promise = import("pg").then(({ Pool }) => {
    const ssl = /sslmode=require|neon\.tech|supabase|render\.com|amazonaws\.com/i.test(connectionString)
      ? { rejectUnauthorized: true }
      : undefined;
    const instance = new Pool({
      connectionString,
      ssl,
      max: 4,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
      statement_timeout: 12_000,
      application_name: "inkar-storefront-read",
    });
    instance.on("error", (error) => console.error("Unexpected storefront database error", error));
    return instance;
  });
  root.__inkarStorefrontReadPool = promise;
  void promise.catch(() => {
    if (root.__inkarStorefrontReadPool === promise) delete root.__inkarStorefrontReadPool;
  });
  return promise;
}

function integer(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

export function verifiedSnapshotExpected(row: RunRow | undefined): number | null {
  const metrics = row?.metrics;
  if (!row || row.status !== "completed" || !metrics || Array.isArray(metrics)) return null;
  const catalogTotal = integer(metrics.catalog_total);
  if (metrics.checkpoint_eligible === true && catalogTotal !== null) return catalogTotal;
  const expected = integer(row.expected_products);
  const processed = integer(row.processed_products);
  if (expected === null || processed !== expected || metrics.complete !== true
      || metrics.start_offset !== 0 || metrics.started_offset !== 0 || metrics.limit !== null) return null;
  return expected;
}

async function verifySnapshot(client: LocalClient): Promise<number> {
  const run = await client.query<RunRow>(`
    SELECT status, expected_products, processed_products, metrics
    FROM catalog_import_runs
    WHERE status = 'completed'
      AND jsonb_typeof(metrics) = 'object'
      AND (
        (
          metrics @> '{"checkpoint_eligible":true}'::jsonb
          AND jsonb_typeof(metrics->'catalog_total') = 'number'
        )
        OR (
          expected_products = processed_products
          AND metrics @> '{"complete":true,"start_offset":0,"started_offset":0,"limit":null}'::jsonb
        )
      )
    ORDER BY finished_at DESC NULLS LAST, started_at DESC, id DESC
    LIMIT 1
  `);
  const expected = verifiedSnapshotExpected(run.rows[0]);
  if (expected === null) throw new Error("catalog_snapshot_incomplete");
  const count = await client.query<{ active_count: unknown }>(`
    SELECT count(*)::integer AS active_count FROM catalog_products WHERE active
  `);
  if (integer(count.rows[0]?.active_count) !== expected) throw new Error("catalog_snapshot_incomplete");
  return expected;
}

async function verifiedRead<T>(read: (client: LocalClient, expected: number) => Promise<T>): Promise<T> {
  const client = await (await pool()).connect();
  let transaction = false;
  try {
    await client.query("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY");
    transaction = true;
    const expected = await verifySnapshot(client);
    const result = await read(client, expected);
    await client.query("COMMIT");
    transaction = false;
    return result;
  } catch (error) {
    if (transaction) await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    client.release();
  }
}

function boundedLimit(value: number, fallback = 250): number {
  return Math.max(1, Math.min(250, Number.isSafeInteger(value) ? value : fallback));
}

export async function getLocalProductsPage(limit = 250): Promise<Product[]> {
  return verifiedRead(async (client) => {
    const result = await client.query<ProductRow>(`
      SELECT ${PRODUCT_COLUMNS}
      FROM catalog_product_read_model
      WHERE active
      ORDER BY id
      LIMIT $1
    `, [boundedLimit(limit)]);
    return result.rows.map((row) => mapRow(row));
  });
}

export async function getLocalProduct(handle: string): Promise<Product | null> {
  return verifiedRead(async (client) => {
    const result = await client.query<ProductRow>(`
      SELECT ${PRODUCT_COLUMNS}
      FROM catalog_product_read_model
      WHERE active AND lower(handle) = lower($1)
      LIMIT 1
    `, [handle]);
    return result.rows[0] ? mapRow(result.rows[0], true) : null;
  });
}

function escapedLike(query: string): string {
  return `%${query.replace(/[\\%_]/g, "\\$&")}%`;
}

export async function searchLocalProducts(query: string, limit = 40): Promise<Product[]> {
  return verifiedRead(async (client) => {
    const like = escapedLike(query);
    // Do not search the denormalized read view directly. That view builds
    // images, categories, variants and pharmacy prices before applying the
    // text predicate, which turns a three-result search into a multi-second
    // scan over the complete offer table. Resolve the small candidate id set
    // from indexed base tables first, then hydrate only those products.
    const candidates = await client.query<{ id: string }>(`
      SELECT product.id
      FROM catalog_products product
      WHERE product.active AND (
        product.search_vector @@ plainto_tsquery('russian', $1)
        OR product.title ILIKE $2 ESCAPE '\\'
        OR EXISTS (
          SELECT 1
          FROM catalog_variants searched_variant
          WHERE searched_variant.product_id = product.id
            AND searched_variant.active
            AND (
              searched_variant.sku ILIKE $2 ESCAPE '\\'
              OR searched_variant.barcode ILIKE $2 ESCAPE '\\'
            )
        )
      )
      ORDER BY product.title, product.id
      LIMIT $3
    `, [query, like, boundedLimit(limit, 40)]);
    if (!candidates.rows.length) return [];

    const ids = candidates.rows.map((row) => row.id);
    const result = await client.query<ProductRow>(`
      SELECT ${PRODUCT_COLUMNS}
      FROM catalog_product_read_model
      WHERE id = ANY($1::text[])
    `, [ids]);
    const byId = new Map(result.rows.map((row) => [row.id, mapRow(row)]));
    return ids.map((id) => byId.get(id)).filter(Boolean) as Product[];
  });
}

function brandKey(value: string): string {
  return value.toLowerCase().replace(/[^a-zа-я0-9]+/gi, "-").replace(/^-+|-+$/g, "").slice(0, 48) || "brand";
}

export async function getLocalBrandProducts(slug: string, limit = 250): Promise<Product[]> {
  return verifiedRead(async (client) => {
    const brands = await client.query<{ brand: string }>(`
      SELECT brand FROM catalog_products
      WHERE active AND brand IS NOT NULL AND btrim(brand) <> ''
        AND btrim(brand) NOT IN ('-', '—', '_')
      GROUP BY brand ORDER BY brand LIMIT 5000
    `);
    const brand = brands.rows.find((row) => brandKey(row.brand) === slug)?.brand;
    if (!brand) return [];
    const result = await client.query<ProductRow>(`
      SELECT ${PRODUCT_COLUMNS}
      FROM catalog_product_read_model
      WHERE active AND brand = $1
      ORDER BY id
      LIMIT $2
    `, [brand, boundedLimit(limit)]);
    return result.rows.map((row) => mapRow(row));
  });
}

export async function getLocalBrandCounts(): Promise<Array<{ name: string; count: number }>> {
  return verifiedRead(async (client) => {
    const result = await client.query<{ name: string; count: unknown }>(`
      SELECT brand AS name, count(*)::integer AS count
      FROM catalog_products
      WHERE active AND brand IS NOT NULL AND btrim(brand) <> ''
        AND btrim(brand) NOT IN ('-', '—', '_')
      GROUP BY brand
      ORDER BY count(*) DESC, brand
      LIMIT 5000
    `);
    return result.rows.flatMap((row) => {
      const count = integer(row.count);
      return count == null ? [] : [{ name: row.name, count }];
    });
  });
}

export async function getLocalCategoryProducts(
  handle: string,
  limit = 60,
): Promise<{ products: Product[]; count: number }> {
  return verifiedRead(async (client) => {
    const params = [handle, boundedLimit(limit, 60)];
    const products = await client.query<ProductRow>(`
      WITH RECURSIVE selected_categories AS (
        SELECT id FROM catalog_categories WHERE active AND lower(handle) = lower($1)
        UNION
        SELECT child.id FROM catalog_categories child
        JOIN selected_categories parent ON child.parent_id = parent.id
        WHERE child.active
      )
      SELECT ${PRODUCT_COLUMNS.replaceAll(/\b(id|handle|active)\b/g, "read.$1")}
      FROM catalog_product_read_model read
      WHERE read.active AND EXISTS (
        SELECT 1 FROM catalog_product_categories link
        JOIN selected_categories selected ON selected.id = link.category_id
        WHERE link.product_id = read.id
      )
      ORDER BY read.id
      LIMIT $2
    `, params);
    const count = await client.query<{ count: unknown }>(`
      WITH RECURSIVE selected_categories AS (
        SELECT id FROM catalog_categories WHERE active AND lower(handle) = lower($1)
        UNION
        SELECT child.id FROM catalog_categories child
        JOIN selected_categories parent ON child.parent_id = parent.id
        WHERE child.active
      )
      SELECT count(DISTINCT link.product_id)::integer AS count
      FROM catalog_product_categories link
      JOIN selected_categories selected ON selected.id = link.category_id
      JOIN catalog_products product ON product.id = link.product_id AND product.active
    `, [handle]);
    return { products: products.rows.map((row) => mapRow(row)), count: integer(count.rows[0]?.count) ?? 0 };
  });
}

const TECHNICAL_HANDLES = new Set(["site", "root", "website"]);

export function buildCategoryTree(rows: CategoryRow[]): CatNode[] {
  const knownIds = new Set(rows.map((row) => row.id));
  const byParent = new Map<string | null, CategoryRow[]>();
  for (const row of rows) {
    const parent = row.parent_id && knownIds.has(row.parent_id) ? row.parent_id : null;
    const siblings = byParent.get(parent) || [];
    siblings.push(row);
    byParent.set(parent, siblings);
  }
  for (const siblings of byParent.values()) {
    siblings.sort((left, right) => left.rank - right.rank || left.name.localeCompare(right.name, "ru"));
  }
  const map = (items: CategoryRow[], path: Set<string>, depth: number): CatNode[] => {
    if (depth > 12) return [];
    return items.flatMap((row) => {
      const handle = text(row.handle);
      if (!handle || path.has(row.id)) return [];
      return [{ id: row.id, handle, name: row.name, children: map(byParent.get(row.id) || [], new Set(path).add(row.id), depth + 1) }];
    });
  };
  const technicalRoots = rows.filter((row) => TECHNICAL_HANDLES.has(String(row.handle || "").toLowerCase()));
  const roots = technicalRoots.length
    ? technicalRoots.flatMap((row) => byParent.get(row.id) || [])
    : (byParent.get(null) || []).filter((row) => !TECHNICAL_HANDLES.has(String(row.handle || "").toLowerCase()));
  return map(roots, new Set(technicalRoots.map((row) => row.id)), 0);
}

export async function getLocalCategoryTree(): Promise<CatNode[]> {
  return verifiedRead(async (client) => {
    const result = await client.query<CategoryRow>(`
      SELECT id, handle, name, parent_id, rank
      FROM catalog_categories WHERE active
      ORDER BY rank, name, id
    `);
    return buildCategoryTree(result.rows);
  });
}

function jsonObjects(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) return value.filter((item): item is Record<string, unknown> => (
    Boolean(item) && typeof item === "object" && !Array.isArray(item)
  ));
  if (typeof value !== "string") return [];
  try {
    return jsonObjects(JSON.parse(value));
  } catch {
    return [];
  }
}

export function mergeLocalBrandFacets(value: unknown): CatalogBrandFacet[] {
  const byKey = new Map<string, { name: string; count: number }>();
  for (const item of jsonObjects(value)) {
    const name = text(item.name);
    const count = integer(item.count);
    if (!name || count == null || count === 0 || ["-", "вЂ”", "_"].includes(name)) continue;
    const key = brandKey(name);
    const current = byKey.get(key);
    if (current) current.count += count;
    else byKey.set(key, { name, count });
  }
  return [...byKey.entries()]
    .map(([key, value]) => ({ key, ...value }))
    .sort((left, right) => right.count - left.count || left.name.localeCompare(right.name, "ru"));
}

export function localCatalogFacetsFromRow(row: FacetAggregateRow | undefined): {
  count: number;
  facets: CatalogFacets;
} {
  const count = integer(row?.count) ?? 0;
  const knownPrices = integer(row?.known_price_count) ?? 0;
  const inStock = integer(row?.in_stock_count) ?? 0;
  const sale = integer(row?.sale_count) ?? 0;
  const rx = integer(row?.rx_count) ?? 0;
  const categories = jsonObjects(row?.categories).flatMap((item) => {
    const id = text(item.id);
    const slug = text(item.slug);
    const name = text(item.name);
    const categoryCount = integer(item.count);
    return id && slug && name && categoryCount != null
      ? [{ id, slug, name, count: categoryCount }]
      : [];
  });
  return {
    count,
    facets: {
      categories,
      brands: mergeLocalBrandFacets(row?.brands),
      price: {
        min: price(row?.min_price),
        max: price(row?.max_price),
        unknown: Math.max(0, count - knownPrices),
      },
      // There is no authoritative negative stock feed in the current model.
      // A positive variant/pharmacy price confirms availability; otherwise the
      // product remains unknown rather than being reported out of stock.
      availability: { inStock, outOfStock: 0, unknown: Math.max(0, count - inStock) },
      sale: { onSale: sale, regular: Math.max(0, count - sale) },
      prescription: { rx, otc: Math.max(0, count - rx) },
    },
  };
}

export function buildFullCatalogFacetsSql(
  categoryCte: string,
  priceJoins: string,
  catalogPrice: string,
  filter: string,
): string {
  const withPrefix = categoryCte ? `${categoryCte},` : "WITH RECURSIVE";
  return `${withPrefix}
    filtered_products AS MATERIALIZED (
      SELECT
        product.id,
        product.brand,
        product.rx_otc,
        ${catalogPrice} AS catalog_price,
        EXISTS (
          SELECT 1 FROM catalog_variants sale_variant
          WHERE sale_variant.product_id = product.id AND sale_variant.active
            AND sale_variant.price_amount > 0
            AND sale_variant.original_price_amount > sale_variant.price_amount
        ) AS on_sale
      FROM catalog_products product
      ${priceJoins}
      WHERE ${filter}
    ),
    technical_roots AS MATERIALIZED (
      SELECT id
      FROM catalog_categories
      WHERE active AND lower(coalesce(handle, '')) IN ('site', 'root', 'website')
    ),
    top_level_categories AS MATERIALIZED (
      SELECT category.id, category.handle, category.name, category.rank
      FROM catalog_categories category
      WHERE category.active
        AND category.handle IS NOT NULL
        AND btrim(category.handle) <> ''
        AND lower(category.handle) NOT IN ('site', 'root', 'website')
        AND (
          category.parent_id IN (SELECT id FROM technical_roots)
          OR (
            NOT EXISTS (SELECT 1 FROM technical_roots)
            AND NOT EXISTS (
              SELECT 1 FROM catalog_categories parent
              WHERE parent.id = category.parent_id AND parent.active
            )
          )
        )
    ),
    category_descendants(ancestor_id, descendant_id) AS (
      SELECT id, id FROM top_level_categories
      UNION
      SELECT tree.ancestor_id, child.id
      FROM category_descendants tree
      JOIN catalog_categories child ON child.parent_id = tree.descendant_id
      WHERE child.active
    ),
    category_counts AS (
      SELECT tree.ancestor_id, count(DISTINCT filtered.id)::integer AS count
      FROM category_descendants tree
      JOIN catalog_product_categories link ON link.category_id = tree.descendant_id
      JOIN filtered_products filtered ON filtered.id = link.product_id
      GROUP BY tree.ancestor_id
    ),
    brand_counts AS (
      SELECT btrim(brand) AS name, count(*)::integer AS count
      FROM filtered_products
      WHERE brand IS NOT NULL AND btrim(brand) <> ''
        AND btrim(brand) NOT IN ('-', 'вЂ”', '_')
      GROUP BY btrim(brand)
    ),
    facet_totals AS (
      SELECT
        count(*)::integer AS count,
        min(catalog_price) AS min_price,
        max(catalog_price) AS max_price,
        count(catalog_price)::integer AS known_price_count,
        count(*) FILTER (WHERE catalog_price IS NOT NULL)::integer AS in_stock_count,
        count(*) FILTER (WHERE on_sale)::integer AS sale_count,
        count(*) FILTER (WHERE lower(coalesce(rx_otc, '')) = 'rx')::integer AS rx_count
      FROM filtered_products
    )
    SELECT
      totals.*,
      coalesce((
        SELECT jsonb_agg(
          jsonb_build_object('name', brands.name, 'count', brands.count)
          ORDER BY brands.count DESC, brands.name
        )
        FROM brand_counts brands
      ), '[]'::jsonb) AS brands,
      coalesce((
        SELECT jsonb_agg(
          jsonb_build_object(
            'id', category.id,
            'slug', category.handle,
            'name', category.name,
            'count', coalesce(counts.count, 0)
          )
          ORDER BY category.rank, category.name, category.id
        )
        FROM top_level_categories category
        LEFT JOIN category_counts counts ON counts.ancestor_id = category.id
      ), '[]'::jsonb) AS categories
    FROM facet_totals totals`;
}

export type LocalCatalogQueryPage = {
  products: Product[];
  count: number;
  catalogTotal: number;
  facets?: CatalogFacets;
};

export function catalogQueryNeedsPrice(query: CatalogQuery): boolean {
  return query.includeFacets
    || query.minPrice != null
    || query.maxPrice != null
    || query.inStock
    || query.sort === "price_asc"
    || query.sort === "price_desc";
}

export async function queryLocalCatalog(query: CatalogQuery): Promise<LocalCatalogQueryPage> {
  return verifiedRead(async (client, expected) => {
    const values: unknown[] = [];
    const where = ["product.active"];
    let categoryCte = "";
    const parameter = (value: unknown) => {
      values.push(value);
      return `$${values.length}`;
    };
    // Resolve text matches separately so PostgreSQL can use the dedicated
    // title/SKU/barcode indexes. Keeping these predicates in one correlated
    // OR made every catalogue request scan variants for all 27k products.
    let searchProductIds: string[] | null = null;
    if (query.q) {
      const searchMatches = await client.query<{ id: string }>(`
        SELECT matched.id
        FROM (
          SELECT product.id
          FROM catalog_products product
          WHERE product.active AND product.title ILIKE $1 ESCAPE '\\'
          UNION
          SELECT product.id
          FROM catalog_products product
          WHERE product.active AND lower(product.brand) = lower($2)
          UNION
          SELECT variant.product_id AS id
          FROM catalog_variants variant
          JOIN catalog_products product ON product.id = variant.product_id AND product.active
          WHERE variant.active
            AND (upper(variant.sku) = upper($2) OR variant.barcode = $2)
        ) matched
        ORDER BY matched.id
      `, [escapedLike(query.q), query.q]);
      searchProductIds = searchMatches.rows.map(({ id }) => id);
    }
    // Aggregate each price source once per query rather than running two
    // correlated subqueries for every one of the 27k catalogue rows. Medusa's
    // list has no calculated price, so pharmacy offers are the fallback.
    const priceJoins = catalogQueryNeedsPrice(query) ? `
      LEFT JOIN (
        SELECT price_variant.product_id, min(price_variant.price_amount) AS min_price
        FROM catalog_variants price_variant
        WHERE price_variant.active AND price_variant.price_amount > 0
        GROUP BY price_variant.product_id
      ) variant_price_filter ON variant_price_filter.product_id = product.id
      LEFT JOIN (
        SELECT price_offer.product_id, min(price_offer.price_amount) AS min_price
        FROM catalog_pharmacy_offers price_offer
        JOIN catalog_pharmacies price_pharmacy
          ON price_pharmacy.id = price_offer.pharmacy_id AND price_pharmacy.active
        WHERE price_offer.in_stock AND price_offer.price_amount > 0
        GROUP BY price_offer.product_id
      ) offer_price_filter ON offer_price_filter.product_id = product.id
    ` : "";
    const catalogPrice = "coalesce(variant_price_filter.min_price, offer_price_filter.min_price)";

    if (query.q) {
      if (searchProductIds?.length) {
        where.push(`product.id = ANY(${parameter(searchProductIds)}::text[])`);
      } else {
        where.push("false");
      }
    }
    if (query.category) {
      const category = parameter(query.category);
      categoryCte = `WITH RECURSIVE selected_categories AS (
        SELECT id FROM catalog_categories WHERE active AND lower(handle) = lower(${category})
        UNION
        SELECT child.id FROM catalog_categories child
        JOIN selected_categories parent ON child.parent_id = parent.id
        WHERE child.active
      )`;
      where.push(`EXISTS (
        SELECT 1 FROM catalog_product_categories link
        JOIN selected_categories selected ON selected.id = link.category_id
        WHERE link.product_id = product.id
      )`);
    }
    if (query.brands.length) {
      const brands = await client.query<{ brand: string }>(`
        SELECT brand FROM catalog_products
        WHERE active AND brand IS NOT NULL AND btrim(brand) <> ''
          AND btrim(brand) NOT IN ('-', '—', '_')
        GROUP BY brand ORDER BY brand LIMIT 5000
      `);
      const requested = new Set(query.brands);
      const names = brands.rows.filter((row) => requested.has(brandKey(row.brand))).map((row) => row.brand);
      if (!names.length) where.push("false");
      else where.push(`product.brand = ANY(${parameter(names)}::text[])`);
    }
    if (query.minPrice != null) where.push(`${catalogPrice} >= ${parameter(query.minPrice)}`);
    if (query.maxPrice != null) where.push(`${catalogPrice} <= ${parameter(query.maxPrice)}`);
    if (query.inStock) where.push(`${catalogPrice} IS NOT NULL`);
    if (query.sale) {
      where.push(`EXISTS (
        SELECT 1 FROM catalog_variants sale_variant
        WHERE sale_variant.product_id = product.id AND sale_variant.active
          AND sale_variant.price_amount > 0
          AND sale_variant.original_price_amount > sale_variant.price_amount
      )`);
    }
    if (query.prescription === "rx") where.push("lower(coalesce(product.rx_otc, '')) = 'rx'");
    if (query.prescription === "otc") where.push("lower(coalesce(product.rx_otc, '')) <> 'rx'");

    const priceOrder = catalogPrice;
    const order = query.sort === "name_asc" ? "product.title ASC, product.id ASC"
      : query.sort === "name_desc" ? "product.title DESC, product.id ASC"
      : query.sort === "price_asc" ? `${priceOrder} ASC NULLS LAST, product.id ASC`
      : query.sort === "price_desc" ? `${priceOrder} DESC NULLS LAST, product.id ASC`
      : "product.id ASC";
    const filter = where.join(" AND ");
    const filtered = Boolean(query.q || query.category || query.brands.length || query.minPrice != null
      || query.maxPrice != null || query.inStock || query.sale || query.prescription !== "all");
    let count: number | undefined;
    let facets: CatalogFacets | undefined;
    if (query.includeFacets) {
      const facetResult = await client.query<FacetAggregateRow>(
        buildFullCatalogFacetsSql(categoryCte, priceJoins, catalogPrice, filter),
        values,
      );
      const aggregate = localCatalogFacetsFromRow(facetResult.rows[0]);
      count = aggregate.count;
      facets = aggregate.facets;
    }
    const pageCountProjection = query.includeFacets
      ? ""
      : ", count(*) OVER()::integer AS filtered_count";
    const pageValues = [...values, query.limit, query.offset];
    const pageIds = await client.query<{ id: string; filtered_count: unknown }>(`
      ${categoryCte}
      SELECT product.id${pageCountProjection}
      FROM catalog_products product
      ${priceJoins}
      WHERE ${filter}
      ORDER BY ${order}
      LIMIT $${values.length + 1} OFFSET $${values.length + 2}
    `, pageValues);
    if (count == null) count = integer(pageIds.rows[0]?.filtered_count) ?? undefined;
    // A valid load-more request normally has at least one row. Preserve a
    // correct count if data changed between pages or a caller supplied an
    // offset beyond the end, without paying for full facet aggregation.
    if (count == null) {
      const countResult = await client.query<{ filtered_count: unknown }>(`
        ${categoryCte}
        SELECT count(*)::integer AS filtered_count
        FROM catalog_products product
        ${priceJoins}
        WHERE ${filter}
      `, values);
      count = integer(countResult.rows[0]?.filtered_count) ?? 0;
    }
    if (!filtered && count !== expected) throw new Error("catalog_snapshot_incomplete");
    if (!pageIds.rows.length) return { products: [], count, catalogTotal: expected, facets };
    const ids = pageIds.rows.map((row) => row.id);
    const result = await client.query<ProductRow>(`
      SELECT ${PRODUCT_COLUMNS}
      FROM catalog_product_read_model
      WHERE id = ANY($1::text[])
    `, [ids]);
    const byId = new Map(result.rows.map((row) => [row.id, mapRow(row)]));
    return {
      products: ids.map((id) => byId.get(id)).filter(Boolean) as Product[],
      count,
      catalogTotal: expected,
      facets,
    };
  });
}
