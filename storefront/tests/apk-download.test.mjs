import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { resolveSingleByteRange } from "../src/lib/apk-download.ts";

const ROUTE = new URL("../src/app/downloads/android/route.ts", import.meta.url);

test("APK download accepts full, bounded, open-ended, and suffix ranges", () => {
  assert.deepEqual(resolveSingleByteRange(null, 1_000), { kind: "full" });
  assert.deepEqual(resolveSingleByteRange("bytes=100-199", 1_000), {
    kind: "partial",
    start: 100,
    end: 199,
    length: 100,
  });
  assert.deepEqual(resolveSingleByteRange("bytes=900-", 1_000), {
    kind: "partial",
    start: 900,
    end: 999,
    length: 100,
  });
  assert.deepEqual(resolveSingleByteRange("bytes=-250", 1_000), {
    kind: "partial",
    start: 750,
    end: 999,
    length: 250,
  });
  assert.deepEqual(resolveSingleByteRange("bytes=950-5000", 1_000), {
    kind: "partial",
    start: 950,
    end: 999,
    length: 50,
  });
});

test("APK download rejects malformed, multipart, and unsatisfiable ranges", () => {
  for (const value of [
    "items=0-9",
    "bytes=",
    "bytes=-0",
    "bytes=1000-",
    "bytes=500-100",
    "bytes=0-1,4-5",
    "bytes=9007199254740992-",
  ]) {
    assert.deepEqual(
      resolveSingleByteRange(value, 1_000),
      { kind: "unsatisfiable" },
      value,
    );
  }
  assert.deepEqual(
    resolveSingleByteRange("bytes=0-0", 0),
    { kind: "unsatisfiable" },
  );
});

test("APK route is fixed-path, metadata-only for HEAD, and streamed for GET", async () => {
  const source = await readFile(ROUTE, "utf8");

  assert.match(source, /const APK_PATH = "\/app\/public\/uploads\/mobile\/apteka-so-sklada\.apk"/);
  assert.match(source, /export async function HEAD/);
  assert.match(source, /export async function GET/);
  assert.match(source, /fs\.stat\(\/\* turbopackIgnore: true \*\/ APK_PATH\)/);
  assert.match(source, /createReadStream\(\/\* turbopackIgnore: true \*\/ APK_PATH/);
  assert.match(source, /Readable\.toWeb\(nodeStream\)/);
  assert.match(source, /application\/vnd\.android\.package-archive/);
  assert.match(source, /"accept-ranges": "bytes"/);
  assert.match(source, /"content-disposition": `attachment; filename="/);
  assert.match(source, /status: 404/);
  assert.match(source, /status: 416/);
  assert.doesNotMatch(source, /readFile|arrayBuffer|request\.nextUrl|searchParams/);
});
