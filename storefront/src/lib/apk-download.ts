export type ByteRangeResolution =
  | { kind: "full" }
  | { kind: "partial"; start: number; end: number; length: number }
  | { kind: "unsatisfiable" };

function parseSafeInteger(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

/** Resolves one RFC-style byte range. Multipart and malformed ranges fail closed. */
export function resolveSingleByteRange(
  value: string | null,
  size: number,
): ByteRangeResolution {
  if (!value) return { kind: "full" };
  if (!Number.isSafeInteger(size) || size < 0) {
    return { kind: "unsatisfiable" };
  }

  const match = /^bytes=(\d*)-(\d*)$/i.exec(value);
  if (!match || (!match[1] && !match[2]) || size === 0) {
    return { kind: "unsatisfiable" };
  }

  if (!match[1]) {
    const suffixLength = parseSafeInteger(match[2]);
    if (suffixLength === null || suffixLength === 0) {
      return { kind: "unsatisfiable" };
    }
    const length = Math.min(suffixLength, size);
    return {
      kind: "partial",
      start: size - length,
      end: size - 1,
      length,
    };
  }

  const start = parseSafeInteger(match[1]);
  const requestedEnd = match[2] ? parseSafeInteger(match[2]) : size - 1;
  if (
    start === null
    || requestedEnd === null
    || start >= size
    || requestedEnd < start
  ) {
    return { kind: "unsatisfiable" };
  }

  const end = Math.min(requestedEnd, size - 1);
  return {
    kind: "partial",
    start,
    end,
    length: end - start + 1,
  };
}
