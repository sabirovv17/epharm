export type ProductReaders<T> = {
  local: (handle: string) => Promise<T | null | undefined>;
  fallback: (handle: string) => Promise<T | null>;
};

/**
 * Resolve a PDP record from the verified local catalogue before touching the
 * upstream store. `null` means that the local snapshot does not contain the
 * handle; `undefined` means that the local source is disabled or unavailable.
 * Both states may use the upstream fallback, while a local record is returned
 * verbatim so its offer price and complete media gallery cannot be overwritten.
 */
export async function resolveLocalFirstProduct<T>(
  handle: string,
  readers: ProductReaders<T>,
): Promise<T | null> {
  let local: T | null | undefined;
  try {
    local = await readers.local(handle);
  } catch {
    local = undefined;
  }
  if (local != null) return local;
  return readers.fallback(handle);
}
