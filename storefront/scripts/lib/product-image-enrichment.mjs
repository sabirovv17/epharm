import { createHash } from "node:crypto";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function normalizeBarcode(value) {
  const digits = String(value || "").replace(/\D/g, "");
  if (digits.length === 8 || digits.length === 13 || digits.length === 14) return digits;
  if (digits.length >= 9 && digits.length <= 12) return digits.padStart(13, "0");
  if (digits.length > 0 && digits.length <= 7) return digits.padStart(8, "0");
  return null;
}

export function barcodeFromOpenFactsKey(value) {
  const match = String(value || "").match(/(?:^|\/)data\/(\d{3})\/(\d{3})\/(\d{3})\/(\d+)(?:\/|$)/);
  return match ? `${match[1]}${match[2]}${match[3]}${match[4]}` : null;
}

export function openFactsImageScore(value) {
  const key = String(value || "").toLowerCase();
  if (!/\.(?:jpg|jpeg|png|webp)$/.test(key)) return -1;
  if (/\/front_ru\.\d+\.400\.jpg$/.test(key)) return 100;
  if (/\/front_en\.\d+\.400\.jpg$/.test(key)) return 95;
  if (/\/front_[a-z]{2}\.\d+\.400\.jpg$/.test(key)) return 90;
  if (/\/front\.\d+\.400\.jpg$/.test(key)) return 85;
  if (/\/\d+\.400\.jpg$/.test(key)) return 60;
  if (/\/front_ru\.\d+\.full\.jpg$/.test(key)) return 55;
  if (/\/front_[a-z]{2}\.\d+\.full\.jpg$/.test(key)) return 50;
  if (/\/\d+\.jpg$/.test(key)) return 30;
  return 0;
}

export function normalizeWords(value) {
  return new Set(String(value || "")
    .toLocaleLowerCase("ru")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .split(/\s+/)
    .filter((word) => word.length > 1));
}

const HANDLE_TOKEN_ALIASES = new Map([
  ["caps", "caps"], ["capsule", "caps"], ["capsules", "caps"],
  ["kaps", "caps"], ["kapsul", "caps"], ["kapsula", "caps"], ["kapsuly", "caps"],
  ["tab", "tabs"], ["tabs", "tabs"], ["tablet", "tabs"], ["tablets", "tabs"],
  ["tabl", "tabs"], ["tabletka", "tabs"], ["tabletki", "tabs"],
]);

const GENERIC_HANDLE_TOKENS = new Set([
  "mg", "g", "gr", "kg", "ml", "l", "caps", "tabs", "sht", "no", "n",
  "up", "pack", "por", "rastvor", "krem", "gel", "sirop", "sprey",
]);

export function handleTokens(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/^\d+-/, "")
    .replace(/([a-z])([0-9])/g, "$1-$2")
    .replace(/([0-9])([a-z])/g, "$1-$2")
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .map((token) => HANDLE_TOKEN_ALIASES.get(token) || token);
}

export function aurmaHandleScore(productHandle, aurmaHandle) {
  const product = new Set(handleTokens(productHandle));
  const source = new Set(handleTokens(aurmaHandle));
  if (!product.size || !source.size) return 0;
  const sourceNumbers = [...source].filter((token) => /^\d+$/.test(token));
  const productNumbers = new Set([...product].filter((token) => /^\d+$/.test(token)));
  if (sourceNumbers.some((token) => !productNumbers.has(token))) return 0;
  let matchedWeight = 0;
  let sourceWeight = 0;
  for (const token of source) {
    const weight = /^\d+$/.test(token) ? 2 : GENERIC_HANDLE_TOKENS.has(token) ? 0.25 : 1;
    sourceWeight += weight;
    if (product.has(token)) matchedWeight += weight;
  }
  return sourceWeight ? matchedWeight / sourceWeight : 0;
}

export function distinctiveHandleTokens(value) {
  return [...new Set(handleTokens(value).filter((token) =>
    token.length >= 4 && !/^\d+$/.test(token) && !GENERIC_HANDLE_TOKENS.has(token),
  ))];
}

function canonicalHandleToken(value) {
  return String(value || "")
    .replaceAll("shch", "sch")
    .replaceAll("yo", "e")
    .replaceAll("yu", "u")
    .replaceAll("ya", "a")
    .replaceAll("kh", "h")
    .replaceAll("ts", "c")
    .replace(/(?:iy|yy)$/u, "i");
}

export function canonicalHandleTokens(value) {
  return handleTokens(value).map(canonicalHandleToken);
}

export function canonicalHandleScore(productHandle, sourceHandle) {
  const product = new Set(canonicalHandleTokens(productHandle));
  const source = new Set(canonicalHandleTokens(sourceHandle));
  if (!product.size || !source.size) return 0;
  const sourceNumbers = [...source].filter((token) => /^\d+$/.test(token));
  const productNumbers = new Set([...product].filter((token) => /^\d+$/.test(token)));
  if (sourceNumbers.some((token) => !productNumbers.has(token))) return 0;
  let matchedWeight = 0;
  let sourceWeight = 0;
  for (const token of source) {
    const weight = /^\d+$/.test(token) ? 2 : GENERIC_HANDLE_TOKENS.has(token) ? 0.25 : 1;
    sourceWeight += weight;
    if (product.has(token)) matchedWeight += weight;
  }
  return sourceWeight ? matchedWeight / sourceWeight : 0;
}

export function distinctiveCanonicalHandleTokens(value) {
  return [...new Set(canonicalHandleTokens(value).filter((token) =>
    token.length >= 4 && !/^\d+$/.test(token) && !GENERIC_HANDLE_TOKENS.has(token),
  ))];
}

export function titleSimilarity(left, right) {
  const a = normalizeWords(left);
  const b = normalizeWords(right);
  if (!a.size || !b.size) return null;
  let intersection = 0;
  for (const word of a) if (b.has(word)) intersection += 1;
  return intersection / new Set([...a, ...b]).size;
}

export function stableImageUuid(productId, preferredSku) {
  if (UUID_RE.test(String(preferredSku || ""))) return String(preferredSku).toUpperCase();
  const hex = createHash("sha256").update(String(productId)).digest("hex").slice(0, 32).split("");
  hex[12] = "4";
  hex[16] = "8";
  const value = hex.join("");
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`.toUpperCase();
}

export function selectCandidate(current, incoming) {
  if (!incoming) return current;
  if (!current) return incoming;
  const priority = { nkt: 500, manufacturer: 400, aptekaplus: 390, europharma: 375, aurma: 350, openbeautyfacts: 300, openproductsfacts: 250, openfoodfacts: 200 };
  const currentScore = (priority[current.source] || 0) + Number(current.image_score || 0);
  const incomingScore = (priority[incoming.source] || 0) + Number(incoming.image_score || 0);
  return incomingScore > currentScore ? incoming : current;
}

function decodeHtmlAttribute(value) {
  return String(value || "")
    .replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"')
    .replaceAll("&#039;", "'")
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">");
}

export function extractEuropharmaProductLinks(html, baseUrl) {
  const links = new Set();
  for (const match of String(html || "").matchAll(/<a\b[^>]*class="[^"]*card-product__link[^"]*"[^>]*href="([^"]+)"/gi)) {
    try {
      const url = new URL(decodeHtmlAttribute(match[1]), baseUrl);
      if (url.protocol === "https:" && url.hostname.endsWith("europharma.kz")) links.add(url.toString());
    } catch {}
  }
  return [...links];
}

export function parseEuropharmaProductHtml(html) {
  const source = String(html || "");
  const meta = new Map();
  for (const match of source.matchAll(/<meta\b[^>]*itemprop="([^"]+)"[^>]*content="([^"]*)"[^>]*>/gi)) {
    if (!meta.has(match[1].toLowerCase())) meta.set(match[1].toLowerCase(), decodeHtmlAttribute(match[2]));
  }
  const barcode = normalizeBarcode(meta.get("mpn"));
  const image = meta.get("image");
  const name = meta.get("name");
  if (!barcode || !image || !name) return null;
  return {
    barcode,
    raw_barcode: String(meta.get("mpn") || "").replace(/\D/g, ""),
    image,
    name,
    brand: meta.get("brand") || null,
    sku: meta.get("sku") || null,
  };
}

function stripHtml(value) {
  return decodeHtmlAttribute(String(value || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim());
}

export function parseAptekaPlusProductHtml(html) {
  const source = String(html || "");
  const barcodeMatch = source.match(/\u0428\u0442\u0440\u0438\u0445\u043a\u043e\u0434:\s*<\/span>\s*<span[^>]*>(\d{8,14})<\/span>/iu);
  const titleMatch = source.match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/iu);
  const imageMatch = source.match(/https:\/\/bitrix\.aptekaplus\.kz\/cdn\/\?q=[^"'<>\\\s]+/iu);
  const barcode = normalizeBarcode(barcodeMatch?.[1]);
  const name = stripHtml(titleMatch?.[1]);
  const image = decodeHtmlAttribute(imageMatch?.[0]);
  if (!barcode || !name || !image) return null;
  try {
    const imageUrl = new URL(image);
    if (imageUrl.protocol !== "https:" || imageUrl.hostname !== "bitrix.aptekaplus.kz") return null;
  } catch {
    return null;
  }
  return {
    barcode,
    raw_barcode: String(barcodeMatch[1]),
    image,
    name,
  };
}
