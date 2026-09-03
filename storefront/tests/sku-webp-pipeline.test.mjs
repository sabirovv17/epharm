import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import sharp from "sharp";

import { parseArgs, runPipeline } from "../scripts/prepare-sku-webp.mjs";

const UUID_A = "11111111-1111-4111-8111-111111111111";
const UUID_B = "22222222-2222-4222-8222-222222222222";
const quietLogger = { log() {}, warn() {}, error() {} };

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "sku-webp-test-"));
  const imagesDir = join(root, "source");
  const outputDir = join(root, "output");
  const manifestPath = join(root, "manifest.jsonl");
  await mkdir(imagesDir);
  return { root, imagesDir, outputDir, manifestPath };
}

async function createJpeg(path, options = {}) {
  let image = sharp({
    create: {
      width: options.width || 12,
      height: options.height || 8,
      channels: 3,
      background: options.background || { r: 210, g: 40, b: 20 },
    },
  }).jpeg({ quality: 90 });
  if (options.orientation) image = image.withMetadata({ orientation: options.orientation });
  await image.toFile(path);
}

async function createPng(path, background = { r: 20, g: 40, b: 210, alpha: 0.8 }) {
  await sharp({
    create: { width: 9, height: 7, channels: 4, background },
  }).png().toFile(path);
}

test("CLI is dry-run by default and protects unbounded apply", () => {
  const parsed = parseArgs(["--images-dir", "source"]);
  assert.equal(parsed.apply, false);
  assert.throws(
    () => parseArgs(["--images-dir", "source", "--apply"]),
    /requires explicit --confirm-all/,
  );
  assert.equal(
    parseArgs(["--images-dir", "source", "--apply", "--limit", "2"]).limit,
    2,
  );
});

test("dry-run inventories valid UUID images without creating output files", async () => {
  const paths = await fixture();
  await createJpeg(join(paths.imagesDir, `${UUID_A}.jpg`));
  await createPng(join(paths.imagesDir, `${UUID_B}.png`));
  await createJpeg(join(paths.imagesDir, "not-a-uuid.jpg"));

  const result = await runPipeline({
    imagesDir: paths.imagesDir,
    outputDir: paths.outputDir,
    manifestPath: paths.manifestPath,
    concurrency: 2,
    logger: quietLogger,
  });

  assert.deepEqual(
    {
      source_files: result.summary.source_files,
      valid_sources: result.summary.valid_sources,
      eligible: result.summary.eligible,
      quarantined: result.summary.quarantined,
      converted: result.summary.converted,
    },
    { source_files: 3, valid_sources: 2, eligible: 2, quarantined: 1, converted: 0 },
  );
  await assert.rejects(stat(paths.outputDir), { code: "ENOENT" });
  const manifest = await readFile(paths.manifestPath, "utf8");
  assert.match(manifest, /"mode":"dry_run"/);
  assert.match(manifest, /"filename_is_not_canonical_uuid"/);
});

test("apply auto-rotates, strips metadata and publishes only final WebP files", async () => {
  const paths = await fixture();
  await createJpeg(join(paths.imagesDir, `${UUID_A}.jpg`), {
    width: 12,
    height: 8,
    orientation: 6,
  });

  const result = await runPipeline({
    apply: true,
    concurrency: 1,
    imagesDir: paths.imagesDir,
    limit: 1,
    logger: quietLogger,
    manifestPath: paths.manifestPath,
    outputDir: paths.outputDir,
    quality: 80,
  });

  assert.equal(result.summary.converted, 1);
  assert.equal(result.summary.failed, 0);
  const entries = await readdir(paths.outputDir);
  assert.deepEqual(entries, [`${UUID_A}.webp`]);
  const metadata = await sharp(join(paths.outputDir, entries[0])).metadata();
  assert.equal(metadata.format, "webp");
  assert.equal(metadata.width, 8);
  assert.equal(metadata.height, 12);
  assert.equal(metadata.orientation, undefined);
  assert.equal(metadata.exif, undefined);
  assert.equal(metadata.icc, undefined);
  assert.equal(metadata.xmp, undefined);
});

test("resume verifies output hashes and does not rewrite completed files", async () => {
  const paths = await fixture();
  await createPng(join(paths.imagesDir, `${UUID_A}.png`));
  await runPipeline({
    apply: true,
    concurrency: 1,
    imagesDir: paths.imagesDir,
    limit: 1,
    logger: quietLogger,
    manifestPath: paths.manifestPath,
    outputDir: paths.outputDir,
  });
  const output = join(paths.outputDir, `${UUID_A}.webp`);
  const before = await stat(output);

  const resumed = await runPipeline({
    apply: true,
    concurrency: 1,
    imagesDir: paths.imagesDir,
    limit: 1,
    logger: quietLogger,
    outputDir: paths.outputDir,
    resumePath: paths.manifestPath,
  });
  const after = await stat(output);

  assert.equal(resumed.summary.resumed, 1);
  assert.equal(resumed.summary.converted, 0);
  assert.equal(after.mtimeMs, before.mtimeMs);
});

test("different sources for the same UUID are quarantined instead of overwritten", async () => {
  const paths = await fixture();
  const nested = join(paths.imagesDir, "nested");
  await mkdir(nested);
  await createJpeg(join(paths.imagesDir, `${UUID_A}.jpg`), {
    background: { r: 255, g: 0, b: 0 },
  });
  await createPng(join(nested, `${UUID_A}.png`), { r: 0, g: 0, b: 255, alpha: 1 });

  const result = await runPipeline({
    apply: true,
    concurrency: 2,
    confirmAll: true,
    imagesDir: paths.imagesDir,
    logger: quietLogger,
    manifestPath: paths.manifestPath,
    outputDir: paths.outputDir,
  });

  assert.equal(result.summary.converted, 0);
  assert.equal(result.summary.quarantined, 2);
  assert.equal(result.summary.quarantine_reasons.conflicting_sources_for_uuid, 2);
  assert.deepEqual(await readdir(paths.outputDir), []);
});

test("pre-existing unverified destination is quarantined and unchanged", async () => {
  const paths = await fixture();
  await createJpeg(join(paths.imagesDir, `${UUID_A}.jpg`));
  await mkdir(paths.outputDir);
  const destination = join(paths.outputDir, `${UUID_A}.webp`);
  const marker = Buffer.from("do-not-overwrite");
  await writeFile(destination, marker);

  const result = await runPipeline({
    apply: true,
    concurrency: 1,
    confirmAll: true,
    imagesDir: paths.imagesDir,
    logger: quietLogger,
    manifestPath: paths.manifestPath,
    outputDir: paths.outputDir,
  });

  assert.equal(result.summary.converted, 0);
  assert.equal(result.summary.quarantine_reasons.destination_exists_without_verified_resume, 1);
  assert.deepEqual(await readFile(destination), marker);
});
