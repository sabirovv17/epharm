import { createReadStream, promises as fs } from "fs";
import { Readable } from "stream";

import { resolveSingleByteRange } from "@/lib/apk-download";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const APK_PATH = "/app/public/uploads/mobile/apteka-so-sklada.apk";
const APK_FILENAME = "apteka-so-sklada.apk";
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";

type ApkMetadata = {
  size: number;
};

async function getApkMetadata(): Promise<ApkMetadata | null> {
  try {
    const info = await fs.stat(/* turbopackIgnore: true */ APK_PATH);
    if (!info.isFile() || !Number.isSafeInteger(info.size) || info.size < 0) {
      return null;
    }
    return { size: info.size };
  } catch {
    return null;
  }
}

function downloadHeaders() {
  return new Headers({
    "accept-ranges": "bytes",
    "cache-control": "private, no-store",
    "content-disposition": `attachment; filename="${APK_FILENAME}"`,
    "content-type": APK_CONTENT_TYPE,
    "x-content-type-options": "nosniff",
  });
}

async function downloadResponse(request: Request, includeBody: boolean) {
  const metadata = await getApkMetadata();
  if (!metadata) {
    return new Response(null, {
      status: 404,
      headers: {
        "cache-control": "no-store",
        "content-length": "0",
        "x-content-type-options": "nosniff",
      },
    });
  }

  const range = resolveSingleByteRange(request.headers.get("range"), metadata.size);
  const headers = downloadHeaders();

  if (range.kind === "unsatisfiable") {
    headers.set("content-length", "0");
    headers.set("content-range", `bytes */${metadata.size}`);
    return new Response(null, { status: 416, headers });
  }

  if (range.kind === "full") {
    headers.set("content-length", String(metadata.size));
    if (!includeBody) return new Response(null, { status: 200, headers });

    const nodeStream = createReadStream(/* turbopackIgnore: true */ APK_PATH);
    request.signal.addEventListener("abort", () => nodeStream.destroy(), { once: true });
    const body = Readable.toWeb(nodeStream) as ReadableStream<Uint8Array>;
    return new Response(body, { status: 200, headers });
  }

  headers.set("content-length", String(range.length));
  headers.set("content-range", `bytes ${range.start}-${range.end}/${metadata.size}`);
  if (!includeBody) return new Response(null, { status: 206, headers });

  const nodeStream = createReadStream(/* turbopackIgnore: true */ APK_PATH, {
    start: range.start,
    end: range.end,
  });
  request.signal.addEventListener("abort", () => nodeStream.destroy(), { once: true });
  const body = Readable.toWeb(nodeStream) as ReadableStream<Uint8Array>;
  return new Response(body, { status: 206, headers });
}

export async function HEAD(request: Request) {
  return downloadResponse(request, false);
}

export async function GET(request: Request) {
  return downloadResponse(request, true);
}
