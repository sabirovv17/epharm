import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("CDP event ingestion streams through the shared bounded JSON reader", async () => {
  const source = await readFile(
    new URL("../src/app/api/events/route.ts", import.meta.url),
    "utf8",
  );

  assert.match(source, /readBoundedJson<unknown>\(req, MAX_BODY_BYTES\)/);
  assert.match(source, /error instanceof RequestBodyError/);
  assert.match(source, /status: error\.status/);
  assert.doesNotMatch(source, /req\.text\(\)/);
  assert.doesNotMatch(source, /JSON\.parse\(bodyText\)/);

  const boundedRead = source.indexOf("readBoundedJson<unknown>");
  const firstEventRead = source.indexOf("body.events");
  assert.ok(boundedRead >= 0, "events route must use the bounded reader");
  assert.ok(firstEventRead > boundedRead, "the body must be bounded before event fields are read");
});
