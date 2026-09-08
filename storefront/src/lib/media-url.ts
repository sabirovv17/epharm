import { secureMedusaBaseUrl } from "./medusaUrl.ts";

function configuredMedusaBaseUrl(): string | null {
  try {
    return secureMedusaBaseUrl(process.env.MEDUSA_URL);
  } catch {
    return null;
  }
}

const MEDUSA_BASE = configuredMedusaBaseUrl();

/** Convert Medusa HTTP /static links to the storefront HTTPS media endpoint. */
export function medusaMediaUrl(raw: unknown): string | undefined {
  const value = typeof raw === "string" ? raw.trim() : "";
  if (!value) return undefined;
  if (value.startsWith("/api/media/medusa?")) return value;
  if (!MEDUSA_BASE) return value.startsWith("http://") ? undefined : value;

  try {
    const source = new URL(value, MEDUSA_BASE);
    const backend = new URL(MEDUSA_BASE);
    if (source.origin === backend.origin && source.pathname.startsWith("/static/")) {
      return `/api/media/medusa?path=${encodeURIComponent(source.pathname + source.search)}`;
    }
  } catch {
    return value;
  }
  return value.startsWith("http://") ? undefined : value;
}
