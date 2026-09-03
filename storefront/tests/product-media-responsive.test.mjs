import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("product gallery cannot widen the mobile product grid", async () => {
  const detail = await readFile(
    new URL("../src/components/product/ProductDetail.tsx", import.meta.url),
    "utf8",
  );

  assert.match(
    detail,
    /className="mt-6 grid min-w-0 grid-cols-1[^"]*lg:grid-cols-2/,
    "the mobile grid track must use minmax(0, 1fr)",
  );
  assert.match(
    detail,
    /className="min-w-0 lg:col-start-1 lg:row-start-1"/,
    "the gallery grid item must be allowed to shrink",
  );
  assert.match(
    detail,
    /className="no-scrollbar mt-3 flex max-w-full min-w-0[^"]*overflow-x-auto/,
    "many thumbnails must scroll inside the viewport instead of widening the page",
  );
});

test("product hero image keeps a bounded square frame and contains the source", async () => {
  const detail = await readFile(
    new URL("../src/components/product/ProductDetail.tsx", import.meta.url),
    "utf8",
  );
  const productImage = await readFile(
    new URL("../src/components/ui/ProductImage.tsx", import.meta.url),
    "utf8",
  );
  const nextConfig = await readFile(
    new URL("../next.config.ts", import.meta.url),
    "utf8",
  );

  assert.match(detail, /className="aspect-square w-full overflow-hidden/);
  assert.match(detail, /imageClassName="p-6"/);
  assert.match(productImage, /className=\{`relative grid[^`]*overflow-hidden/);
  assert.match(productImage, /<Image[\s\S]*?\n\s+fill\n/);
  assert.match(productImage, /sizes="\(max-width: 640px\) 50vw/);
  assert.match(productImage, /const canOptimize = source\.startsWith\("\/api\/uploads\/"\)/);
  assert.match(productImage, /unoptimized=\{!canOptimize\}/);
  assert.match(productImage, /className=\{`object-contain/);
  assert.match(nextConfig, /\{ pathname: "\/api\/media\/medusa" \}/);
});
