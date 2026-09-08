const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export class MedusaUrlConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MedusaUrlConfigurationError";
  }
}

function isLoopback(url: URL) {
  return LOOPBACK_HOSTS.has(url.hostname.toLowerCase());
}

/**
 * Resolve the configured Medusa origin without allowing credentials or
 * cleartext traffic to a remote host. HTTP remains available only for a
 * loopback development server.
 */
export function secureMedusaBaseUrl(rawValue: string | undefined): string | null {
  const raw = String(rawValue || "").trim();
  if (!raw) return null;

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new MedusaUrlConfigurationError("MEDUSA_URL must be an absolute URL");
  }

  if (url.username || url.password) {
    throw new MedusaUrlConfigurationError("MEDUSA_URL must not contain credentials");
  }
  if (
    url.protocol !== "https:"
    && !(url.protocol === "http:" && isLoopback(url))
  ) {
    throw new MedusaUrlConfigurationError(
      "MEDUSA_URL must use HTTPS; HTTP is allowed only for loopback development",
    );
  }
  if (url.pathname !== "/") {
    throw new MedusaUrlConfigurationError("MEDUSA_URL must be an origin without a path");
  }
  if (url.search || url.hash) {
    throw new MedusaUrlConfigurationError("MEDUSA_URL must not contain a query string or fragment");
  }

  return url.href.replace(/\/+$/, "");
}
