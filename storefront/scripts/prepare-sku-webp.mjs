#!/usr/bin/env node

/**
 * Prepare an extracted SKU image pack for upload as metadata-free WebP files.
 *
 * Safety invariants:
 * - dry-run unless --apply is explicitly present;
 * - an unbounded apply additionally requires --confirm-all;
 * - source files are opened read-only and are never changed;
 * - only canonical UUID JPEG/PNG basenames are accepted;
 * - conflicting files for one UUID are quarantined, never guessed;
 * - destinations are published with an atomic hard-link and never overwritten;
 * - conversion work has bounded concurrency and output size;
 * - an append-only JSONL manifest makes successful work resumable.
 */

import { availableParallelism } from "node:os";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import {
  appendFile,
  link,
  lstat,
  mkdir,
  open,
  readFile,
  readdir,
  stat,
  unlink,
} from "node:fs/promises";
import { basename, dirname, extname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import process from "node:process";
import sharp from "sharp";

const PIPELINE_VERSION = 1;
const DEFAULT_QUALITY = 82;
const MAX_CONCURRENCY = 8;
const MAX_SOURCE_BYTES = 25 * 1024 * 1024;
const MAX_OUTPUT_BYTES = 5 * 1024 * 1024;
const MAX_INPUT_PIXELS = 100_000_000;
const SOURCE_EXTENSIONS = new Set([".jpg", ".jpeg", ".png"]);
const UUID_RE = /^[0-9A-F]{8}-[0-9A-F]{4}-[1-5][0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$/;

function usage() {
  return `
Usage:
  node scripts/prepare-sku-webp.mjs --images-dir <directory> [options]

Options:
  --images-dir <path>   Extracted source directory (required)
  --output-dir <path>   WebP destination (default: <images-dir>-webp)
  --apply               Write WebP files (default: dry-run)
  --confirm-all         Explicitly allow an apply without --limit
  --limit <n>           Convert at most n eligible UUIDs
  --quality <1-100>     WebP quality (default: ${DEFAULT_QUALITY})
  --concurrency <1-${MAX_CONCURRENCY}>
                        Simultaneous conversions (default: CPU-aware, max 4)
  --manifest <path>     New JSONL manifest path
  --resume <path>       Resume and append to an existing JSONL manifest
  --help                Show this help

Examples:
  node scripts/prepare-sku-webp.mjs --images-dir C:\\work\\sku_images
  node scripts/prepare-sku-webp.mjs --images-dir C:\\work\\sku_images --apply --limit 5
  node scripts/prepare-sku-webp.mjs --images-dir C:\\work\\sku_images --apply --confirm-all --resume .\\sku-webp.jsonl
`;
}

function integerOption(name, value, minimum, maximum = Number.MAX_SAFE_INTEGER) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return parsed;
}

export function parseArgs(argv) {
  const args = {
    apply: false,
    confirmAll: false,
    concurrency: Math.max(1, Math.min(4, availableParallelism())),
    imagesDir: "",
    limit: null,
    manifest: "",
    outputDir: "",
    quality: DEFAULT_QUALITY,
    resume: "",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--apply") {
      args.apply = true;
    } else if (argument === "--confirm-all") {
      args.confirmAll = true;
    } else if (argument === "--help" || argument === "-h") {
      args.help = true;
    } else if (
      ["--images-dir", "--output-dir", "--limit", "--quality", "--concurrency", "--manifest", "--resume"].includes(argument)
    ) {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) throw new Error(`${argument} requires a value`);
      index += 1;
      if (argument === "--images-dir") args.imagesDir = value;
      if (argument === "--output-dir") args.outputDir = value;
      if (argument === "--manifest") args.manifest = value;
      if (argument === "--resume") args.resume = value;
      if (argument === "--limit") args.limit = integerOption("--limit", value, 1);
      if (argument === "--quality") args.quality = integerOption("--quality", value, 1, 100);
      if (argument === "--concurrency") {
        args.concurrency = integerOption("--concurrency", value, 1, MAX_CONCURRENCY);
      }
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }

  if (args.help) return args;
  if (!args.imagesDir) throw new Error("--images-dir is required");
  if (args.manifest && args.resume) throw new Error("Use either --manifest or --resume, not both");
  if (args.apply && args.limit === null && !args.confirmAll) {
    throw new Error("An apply without --limit requires explicit --confirm-all");
  }
  if (!args.apply && args.confirmAll) {
    throw new Error("--confirm-all is only valid together with --apply");
  }
  return args;
}

function safeError(error) {
  if (error instanceof Error) return error.message;
  return String(error);
}

function normalizeUuid(value) {
  return String(value || "").trim().toUpperCase();
}

function increment(record, key, count = 1) {
  record[key] = (record[key] || 0) + count;
}

function stableSignature(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

async function sha256File(path) {
  const hash = createHash("sha256");
  const input = createReadStream(path);
  for await (const chunk of input) hash.update(chunk);
  return hash.digest("hex");
}

async function unlinkTemporary(path) {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    try {
      await unlink(path);
      return;
    } catch (error) {
      if (error?.code === "ENOENT") return;
      if (!["EBUSY", "EPERM"].includes(error?.code) || attempt === 7) throw error;
      await new Promise((resolveWait) => {
        setTimeout(resolveWait, 25 * (attempt + 1));
      });
    }
  }
}

async function pathType(path) {
  try {
    const info = await lstat(path);
    if (info.isSymbolicLink()) return "symbolic_link";
    if (info.isFile()) return "file";
    if (info.isDirectory()) return "directory";
    return "other";
  } catch (error) {
    if (error?.code === "ENOENT") return "missing";
    throw error;
  }
}

export async function mapPool(items, concurrency, worker) {
  let cursor = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= items.length) return;
      await worker(items[index], index);
    }
  });
  await Promise.all(workers);
}

async function walkSourceImages(root) {
  const files = [];
  const directories = [root];
  while (directories.length > 0) {
    const directory = directories.pop();
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name, "en"));
    for (const entry of entries) {
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) directories.push(path);
      else if (entry.isFile() && SOURCE_EXTENSIONS.has(extname(entry.name).toLowerCase())) files.push(path);
    }
  }
  return files.sort((left, right) => left.localeCompare(right, "en"));
}

async function readSignature(path) {
  const handle = await open(path, "r");
  try {
    const bytes = Buffer.alloc(12);
    const { bytesRead } = await handle.read(bytes, 0, bytes.length, 0);
    return bytes.subarray(0, bytesRead);
  } finally {
    await handle.close();
  }
}

function signatureKind(bytes) {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpeg";
  if (
    bytes.length >= 8
    && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
  ) {
    return "png";
  }
  if (
    bytes.length >= 12
    && bytes.subarray(0, 4).toString("ascii") === "RIFF"
    && bytes.subarray(8, 12).toString("ascii") === "WEBP"
  ) {
    return "webp";
  }
  return null;
}

async function inspectSource(path, root) {
  const source = relative(root, path).replaceAll("\\", "/");
  const extension = extname(path).toLowerCase();
  const uuid = normalizeUuid(basename(path, extension));
  const info = await stat(path);

  if (!UUID_RE.test(uuid)) return { path, source, uuid, valid: false, reason: "filename_is_not_canonical_uuid" };
  if (info.size === 0) return { path, source, uuid, size: info.size, valid: false, reason: "empty_file" };
  if (info.size > MAX_SOURCE_BYTES) {
    return { path, source, uuid, size: info.size, valid: false, reason: "source_exceeds_25_mib" };
  }

  const expectedKind = extension === ".png" ? "png" : "jpeg";
  if (signatureKind(await readSignature(path)) !== expectedKind) {
    return { path, source, uuid, size: info.size, valid: false, reason: "extension_signature_mismatch" };
  }

  let metadata;
  try {
    metadata = await sharp(path, {
      failOn: "warning",
      limitInputPixels: MAX_INPUT_PIXELS,
      sequentialRead: true,
    }).metadata();
  } catch {
    return { path, source, uuid, size: info.size, valid: false, reason: "image_decode_failed" };
  }
  if (
    metadata.format !== expectedKind
    || !Number.isSafeInteger(metadata.width)
    || !Number.isSafeInteger(metadata.height)
    || metadata.width < 1
    || metadata.height < 1
  ) {
    return { path, source, uuid, size: info.size, valid: false, reason: "invalid_image_metadata" };
  }
  if (Number(metadata.pages || 1) !== 1) {
    return { path, source, uuid, size: info.size, valid: false, reason: "multi_page_source_not_supported" };
  }

  return {
    path,
    source,
    uuid,
    size: info.size,
    width: metadata.width,
    height: metadata.height,
    orientation: metadata.orientation || null,
    sourceFormat: expectedKind,
    sourceSha256: await sha256File(path),
    valid: true,
  };
}

class ManifestWriter {
  constructor(path, runId, needsLeadingNewline = false) {
    this.path = path;
    this.runId = runId;
    this.needsLeadingNewline = needsLeadingNewline;
    this.pending = Promise.resolve();
  }

  write(event) {
    const line = JSON.stringify({
      timestamp: new Date().toISOString(),
      run_id: this.runId,
      ...event,
    });
    this.pending = this.pending.then(async () => {
      if (this.needsLeadingNewline) {
        await appendFile(this.path, "\n", "utf8");
        this.needsLeadingNewline = false;
      }
      await appendFile(this.path, `${line}\n`, "utf8");
    });
    return this.pending;
  }

  flush() {
    return this.pending;
  }
}

async function readResumeState(path, expectedConfigSignature) {
  const raw = await readFile(path, "utf8");
  const lines = raw.split(/\r?\n/);
  const endsWithNewline = /\r?\n$/.test(raw);
  const completedBySource = new Map();
  let ignoredTrailingLine = false;
  let sawMatchingConfiguration = false;

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trim();
    if (!line) continue;
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      const trailingPartial = index === lines.length - 1 && !endsWithNewline;
      if (!trailingPartial) throw new Error(`Invalid JSON in resume manifest at line ${index + 1}`);
      ignoredTrailingLine = true;
      continue;
    }

    if (event.event === "run_started") {
      if (event.config_signature !== expectedConfigSignature) {
        throw new Error("Resume manifest belongs to a different source/output/quality configuration");
      }
      sawMatchingConfiguration = true;
    }
    if (
      event.event === "conversion_succeeded"
      && typeof event.source === "string"
      && typeof event.source_sha256 === "string"
      && typeof event.output_sha256 === "string"
      && typeof event.destination === "string"
    ) {
      completedBySource.set(event.source, {
        sourceSha256: event.source_sha256,
        outputSha256: event.output_sha256,
        destination: event.destination,
      });
    }
  }

  if (!sawMatchingConfiguration) throw new Error("Resume manifest has no compatible run_started event");
  return {
    completedBySource,
    ignoredTrailingLine,
    needsLeadingNewline: !endsWithNewline,
  };
}

async function publishWebp(task, options) {
  const { outputDir, quality, runId } = options;
  const destination = join(outputDir, `${task.uuid}.webp`);
  const temporary = join(outputDir, `.${task.uuid}.${runId}.${randomUUID()}.tmp`);

  try {
    await sharp(task.path, {
      failOn: "warning",
      limitInputPixels: MAX_INPUT_PIXELS,
      sequentialRead: true,
    })
      .rotate()
      .webp({
        quality,
        alphaQuality: Math.max(quality, 90),
        effort: 4,
        smartSubsample: true,
      })
      .toFile(temporary);

    const info = await stat(temporary);
    if (info.size === 0 || info.size > MAX_OUTPUT_BYTES) {
      throw new Error(info.size === 0 ? "empty_webp_output" : "webp_output_exceeds_5_mib");
    }
    if (signatureKind(await readSignature(temporary)) !== "webp") throw new Error("invalid_webp_signature");

    const metadata = await sharp(temporary, {
      failOn: "warning",
      limitInputPixels: MAX_INPUT_PIXELS,
      sequentialRead: true,
    }).metadata();
    if (metadata.format !== "webp" || metadata.orientation || metadata.exif || metadata.icc || metadata.xmp) {
      throw new Error("webp_metadata_verification_failed");
    }

    const outputSha256 = await sha256File(temporary);
    try {
      await link(temporary, destination);
    } catch (error) {
      if (error?.code === "EEXIST") throw new Error("destination_created_concurrently");
      throw error;
    }
    return {
      destinationRelative: relative(outputDir, destination).replaceAll("\\", "/"),
      outputBytes: info.size,
      outputSha256,
      width: metadata.width,
      height: metadata.height,
    };
  } finally {
    await unlinkTemporary(temporary);
  }
}

export async function runPipeline(options) {
  const {
    apply = false,
    confirmAll = false,
    concurrency = Math.max(1, Math.min(4, availableParallelism())),
    imagesDir: rawImagesDir,
    limit = null,
    logger = console,
    manifestPath: rawManifestPath = "",
    outputDir: rawOutputDir = "",
    quality = DEFAULT_QUALITY,
    resumePath: rawResumePath = "",
  } = options;

  if (!rawImagesDir) throw new Error("imagesDir is required");
  if (!Number.isSafeInteger(concurrency) || concurrency < 1 || concurrency > MAX_CONCURRENCY) {
    throw new Error(`concurrency must be from 1 to ${MAX_CONCURRENCY}`);
  }
  if (!Number.isSafeInteger(quality) || quality < 1 || quality > 100) {
    throw new Error("quality must be from 1 to 100");
  }
  if (limit !== null && (!Number.isSafeInteger(limit) || limit < 1)) throw new Error("limit must be positive");
  if (apply && limit === null && !confirmAll) {
    throw new Error("An apply without a limit requires confirmAll");
  }
  if (rawManifestPath && rawResumePath) throw new Error("Use manifestPath or resumePath, not both");

  const imagesDir = resolve(rawImagesDir);
  const outputDir = resolve(rawOutputDir || `${imagesDir}-webp`);
  if (imagesDir === outputDir) throw new Error("outputDir must differ from imagesDir");
  if (await pathType(imagesDir) !== "directory") throw new Error("imagesDir must be a regular directory");
  const outputType = await pathType(outputDir);
  if (!["missing", "directory"].includes(outputType)) {
    throw new Error("outputDir must be absent or a regular directory");
  }

  const configuration = {
    pipeline_version: PIPELINE_VERSION,
    images_dir: imagesDir,
    output_dir: outputDir,
    quality,
    max_source_bytes: MAX_SOURCE_BYTES,
    max_output_bytes: MAX_OUTPUT_BYTES,
  };
  const configSignature = stableSignature(configuration);
  const timestamp = new Date().toISOString().replace(/[.:]/g, "-");
  const manifestPath = resolve(
    rawResumePath || rawManifestPath || `sku-webp-${timestamp}.jsonl`,
  );
  const runId = randomUUID();
  let resumeState = {
    completedBySource: new Map(),
    ignoredTrailingLine: false,
    needsLeadingNewline: false,
  };
  if (rawResumePath) resumeState = await readResumeState(manifestPath, configSignature);
  await mkdir(dirname(manifestPath), { recursive: true });

  const manifest = new ManifestWriter(manifestPath, runId, resumeState.needsLeadingNewline);
  const summary = {
    source_files: 0,
    valid_sources: 0,
    unique_sources: 0,
    eligible: 0,
    selected: 0,
    converted: 0,
    resumed: 0,
    skipped_limit: 0,
    quarantined: 0,
    failed: 0,
    quarantine_reasons: {},
  };

  await manifest.write({
    event: "run_started",
    mode: apply ? "apply" : "dry_run",
    config_signature: configSignature,
    configuration,
    concurrency,
    limit,
    resumed: Boolean(rawResumePath),
    ignored_truncated_resume_line: resumeState.ignoredTrailingLine,
  });

  const paths = await walkSourceImages(imagesDir);
  summary.source_files = paths.length;
  logger.log(`[sku-webp] mode=${apply ? "APPLY" : "DRY-RUN"} source_files=${paths.length}`);
  logger.log(`[sku-webp] manifest=${manifestPath}`);

  const inspected = new Array(paths.length);
  await mapPool(paths, concurrency, async (path, index) => {
    inspected[index] = await inspectSource(path, imagesDir);
  });

  const valid = [];
  for (const source of inspected) {
    if (!source.valid) {
      summary.quarantined += 1;
      increment(summary.quarantine_reasons, source.reason);
      await manifest.write({
        event: "source_classified",
        status: "quarantined",
        quarantine_reason: source.reason,
        source: source.source,
        uuid: source.uuid,
        size: source.size ?? null,
      });
    } else {
      summary.valid_sources += 1;
      valid.push(source);
    }
  }

  const byUuid = new Map();
  for (const source of valid) {
    const group = byUuid.get(source.uuid) || [];
    group.push(source);
    byUuid.set(source.uuid, group);
  }

  const unique = [];
  for (const [uuid, group] of [...byUuid.entries()].sort(([left], [right]) => left.localeCompare(right, "en"))) {
    group.sort((left, right) => left.source.localeCompare(right.source, "en"));
    const hashes = new Set(group.map((source) => source.sourceSha256));
    if (hashes.size > 1) {
      summary.quarantined += group.length;
      increment(summary.quarantine_reasons, "conflicting_sources_for_uuid", group.length);
      for (const source of group) {
        await manifest.write({
          event: "source_classified",
          status: "quarantined",
          quarantine_reason: "conflicting_sources_for_uuid",
          source: source.source,
          uuid,
          source_sha256: source.sourceSha256,
        });
      }
      continue;
    }
    const canonical = group[0];
    unique.push(canonical);
    for (const duplicate of group.slice(1)) {
      await manifest.write({
        event: "source_classified",
        status: "duplicate_source_same_content",
        source: duplicate.source,
        canonical_source: canonical.source,
        uuid,
        source_sha256: duplicate.sourceSha256,
      });
    }
  }
  summary.unique_sources = unique.length;

  const eligible = [];
  for (const source of unique) {
    const destination = join(outputDir, `${source.uuid}.webp`);
    const destinationState = await pathType(destination);
    const resumed = resumeState.completedBySource.get(source.source);

    if (resumed && resumed.sourceSha256 === source.sourceSha256) {
      const recordedDestination = resolve(outputDir, resumed.destination);
      if (recordedDestination !== destination) {
        summary.quarantined += 1;
        increment(summary.quarantine_reasons, "resume_destination_mismatch");
        await manifest.write({
          event: "source_classified",
          status: "quarantined",
          quarantine_reason: "resume_destination_mismatch",
          source: source.source,
          uuid: source.uuid,
        });
        continue;
      }
      if (destinationState === "file" && await sha256File(destination) === resumed.outputSha256) {
        summary.resumed += 1;
        await manifest.write({
          event: "source_classified",
          status: "resumed_verified",
          source: source.source,
          destination: relative(outputDir, destination).replaceAll("\\", "/"),
          uuid: source.uuid,
          source_sha256: source.sourceSha256,
          output_sha256: resumed.outputSha256,
        });
        continue;
      }
      summary.quarantined += 1;
      increment(summary.quarantine_reasons, "resume_output_missing_or_changed");
      await manifest.write({
        event: "source_classified",
        status: "quarantined",
        quarantine_reason: "resume_output_missing_or_changed",
        source: source.source,
        uuid: source.uuid,
      });
      continue;
    }

    if (destinationState !== "missing") {
      summary.quarantined += 1;
      increment(summary.quarantine_reasons, "destination_exists_without_verified_resume");
      await manifest.write({
        event: "source_classified",
        status: "quarantined",
        quarantine_reason: "destination_exists_without_verified_resume",
        source: source.source,
        destination: relative(outputDir, destination).replaceAll("\\", "/"),
        uuid: source.uuid,
      });
      continue;
    }

    eligible.push(source);
    await manifest.write({
      event: "source_classified",
      status: "eligible",
      source: source.source,
      destination: `${source.uuid}.webp`,
      uuid: source.uuid,
      source_format: source.sourceFormat,
      source_sha256: source.sourceSha256,
      source_bytes: source.size,
      width: source.width,
      height: source.height,
      orientation: source.orientation,
    });
  }
  summary.eligible = eligible.length;
  const selected = limit === null ? eligible : eligible.slice(0, limit);
  summary.selected = selected.length;
  summary.skipped_limit = eligible.length - selected.length;

  for (const source of eligible.slice(selected.length)) {
    await manifest.write({
      event: "source_classified",
      status: "skipped_limit",
      source: source.source,
      destination: `${source.uuid}.webp`,
      uuid: source.uuid,
      source_sha256: source.sourceSha256,
    });
  }

  if (!apply) {
    await manifest.write({ event: "run_completed", mode: "dry_run", summary });
    await manifest.flush();
    logger.log(
      `[sku-webp] dry-run complete eligible=${summary.eligible} quarantined=${summary.quarantined}; no images written`,
    );
    return { manifestPath, outputDir, summary };
  }

  await mkdir(outputDir, { recursive: true });
  sharp.cache(false);
  sharp.concurrency(1);

  await mapPool(selected, concurrency, async (source) => {
    await manifest.write({
      event: "conversion_started",
      source: source.source,
      destination: `${source.uuid}.webp`,
      uuid: source.uuid,
      source_sha256: source.sourceSha256,
    });
    try {
      const result = await publishWebp(source, { outputDir, quality, runId });
      summary.converted += 1;
      await manifest.write({
        event: "conversion_succeeded",
        source: source.source,
        destination: result.destinationRelative,
        uuid: source.uuid,
        source_sha256: source.sourceSha256,
        output_sha256: result.outputSha256,
        output_bytes: result.outputBytes,
        width: result.width,
        height: result.height,
      });
    } catch (error) {
      summary.failed += 1;
      await manifest.write({
        event: "conversion_failed",
        source: source.source,
        destination: `${source.uuid}.webp`,
        uuid: source.uuid,
        source_sha256: source.sourceSha256,
        error: safeError(error),
      });
    }
  });

  await manifest.write({ event: "run_completed", mode: "apply", summary });
  await manifest.flush();
  logger.log(
    `[sku-webp] apply complete converted=${summary.converted} resumed=${summary.resumed} failed=${summary.failed}`,
  );
  return { manifestPath, outputDir, summary };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(usage());
    return;
  }
  const result = await runPipeline({
    apply: args.apply,
    confirmAll: args.confirmAll,
    concurrency: args.concurrency,
    imagesDir: args.imagesDir,
    limit: args.limit,
    manifestPath: args.manifest,
    outputDir: args.outputDir,
    quality: args.quality,
    resumePath: args.resume,
  });
  if (result.summary.failed > 0) process.exitCode = 2;
}

const isCli = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isCli) {
  main().catch((error) => {
    console.error(`[sku-webp] fatal: ${safeError(error)}`);
    process.exitCode = 1;
  });
}
