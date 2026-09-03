import type { NextConfig } from "next";

const LEGACY_PROMO_ASSETS = [
  "banner-ivatherm",
  "banner-larimide",
  "banner-lifestyle-family",
  "banner-lifestyle-mom-baby",
  "banner-lifestyle-skincare",
  "banner-lifestyle-wellness",
  "banner-selfielab",
  "card-ivatherm",
  "card-larimide",
  "card-now-d3",
  "card-now-hair",
  "card-now-sleep",
  "card-selfielab",
  "care-allergy",
  "care-energy",
  "care-hydration",
  "care-mom-baby",
  "care-skin",
  "care-sleep",
  "care-sun",
  "category-medical-products",
  "promo-immunity-photo",
  "promo-kbeauty-photo",
  "promo-mom-baby-photo",
  "quick-beauty-unique",
  "quick-family-unique",
  "quick-more-unique",
  "quick-vitamins-unique",
] as const;

const nextConfig: NextConfig = {
  distDir: process.env.NEXT_DIST_DIR || ".next",
  outputFileTracingRoot: process.cwd(),
  turbopack: { root: process.cwd() },
  images: {
    localPatterns: [
      // Dynamic `path` values are intentionally allowed only on this route;
      // medusa-media-proxy validates /static/, rejects traversal/redirects,
      // fixes the upstream origin and accepts image content-types only.
      { pathname: "/api/media/medusa" },
      { pathname: "/api/uploads/**", search: "" },
      { pathname: "/uploads/**", search: "" },
      // Curated, repository-owned storefront artwork (category cards,
      // promotional tiles, banners and stories).
      { pathname: "/promo/**", search: "" },
    ],
  },
  // Лёгкий self-contained сервер для Docker/прода (.next/standalone).
  output: "standalone",
  // Серверные пакеты с нативной/Node-специфичной загрузкой не бандлим.
  serverExternalPackages: ["pg", "sharp", "web-push"],
  async redirects() {
    return LEGACY_PROMO_ASSETS.map((name) => ({
      source: `/promo/${name}.png`,
      destination: `/promo/${name}.webp`,
      permanent: true,
    }));
  },
  async headers() {
    return [
      {
        source: "/:path*",
        has: [{ type: "header", key: "accept", value: ".*text/html.*" }],
        headers: [
          { key: "Cache-Control", value: "no-store, no-cache, must-revalidate, proxy-revalidate" },
        ],
      },
      {
        source: "/sw.js",
        headers: [
          { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
          { key: "Service-Worker-Allowed", value: "/" },
        ],
      },
    ];
  },
};

export default nextConfig;
