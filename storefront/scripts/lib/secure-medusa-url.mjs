const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export function secureMedusaUrl(rawValue) {
  const raw = String(rawValue || "").trim();
  if (!raw) throw new Error("MEDUSA_URL is required");

  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error("MEDUSA_URL must be an absolute URL");
  }
  if (url.username || url.password) {
    throw new Error("Do not embed credentials in MEDUSA_URL");
  }
  const loopbackHttp = url.protocol === "http:"
    && LOOPBACK_HOSTS.has(url.hostname.toLowerCase());
  if (url.protocol !== "https:" && !loopbackHttp) {
    throw new Error("MEDUSA_URL must use HTTPS; HTTP is allowed only for loopback development");
  }
  if (url.pathname !== "/") {
    throw new Error("MEDUSA_URL must be an origin without a path");
  }
  if (url.search || url.hash) {
    throw new Error("MEDUSA_URL must not contain a query string or fragment");
  }

  return url;
}
