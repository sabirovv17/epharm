#!/usr/bin/env python3
"""Offline check of normalized private file references; never print their keys.

Keys are decoded, relative POSIX storage keys, not URLs. Download manifests prove
source metadata only: they never prove that download bytes were restored locally.
The optional output must be a new local file outside the checked roots.
Stdout previews at most 20 source groups and unique failures; output keeps all.
"""

import argparse
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import unicodedata
from collections import Counter, defaultdict


KINDS = {"s3", "merch", "download", "generated", "external", "terminal_local"}
SOURCE = re.compile(r"[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*(?:/[A-Za-z0-9_$.[\]*-]+)?\Z")
SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
PREVIEW_LIMIT = 20
MAX_SOURCE_LENGTH = 256
FAILURES = {"invalid_input", "invalid_reference", "unsafe", "missing", "io_error",
            "root_required", "manifest_required", "manifest_invalid", "size_mismatch",
            "sha256_mismatch", "output_error"}


class CheckError(Exception):
    pass


def key_hash(key):
    return hashlib.sha256(key.encode("utf-8", "surrogatepass")).hexdigest()


def safe_key(key):
    return (isinstance(key, str) and bool(key) and not key.startswith("/")
            and not re.match(r"^[A-Za-z]:", key) and "\\" not in key
            and all(part not in {"", ".", ".."} for part in key.split("/"))
            and not any(unicodedata.category(c) in {"Cc", "Cf", "Cs"} for c in key))


def expected_fields_valid(row):
    digest = row.get("expected_sha256")
    size = row.get("expected_bytes")
    return ((digest is None or isinstance(digest, str) and SHA256.fullmatch(digest))
            and (size is None or type(size) is int and size >= 0))


def read_json(path):
    # Exception text can contain private paths or record values; callers redact it.
    def unique_object(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise CheckError("duplicate_json_key")
            value[key] = item
        return value
    with Path(path).open(encoding="utf-8") as stream:
        return json.load(stream, object_pairs_hook=unique_object)


def hash_file(root, key):
    """Open every component relative to directory FDs without following symlinks."""
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise CheckError("unsupported_platform")
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        parts = key.split("/")
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                     dir_fd=directory)
        with os.fdopen(fd, "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise CheckError("unsafe")
            digest = hashlib.sha256()
            size = 0
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
                size += len(chunk)
        return {"bytes": size, "sha256": digest.hexdigest()}
    finally:
        os.close(directory)


def file_error(exc):
    if isinstance(exc, CheckError):
        return "unsafe" if str(exc) == "unsafe" else "io_error"
    if exc.errno in {errno.ELOOP, errno.ENOTDIR}:
        return "unsafe"
    return "missing" if exc.errno == errno.ENOENT else "io_error"


def result(source, kind, status, key=None):
    row = {"source": source, "kind": kind, "status": status}
    if key is not None:
        row["key_sha256"] = key_hash(key)
    return row


def inventory_root(root, kind, results):
    files = {}
    try:
        path = Path(root)
        if path.is_symlink() or not path.is_dir():
            raise CheckError("unsafe")
        path = path.resolve(strict=True)
        def walk_error(_exc):
            results.append(result("inventory." + kind, kind, "io_error"))
        for directory, dirs, names in os.walk(path, followlinks=False, onerror=walk_error):
            for name in sorted(dirs[:]):
                candidate = Path(directory) / name
                key = candidate.relative_to(path).as_posix()
                if not safe_key(key) or candidate.is_symlink():
                    results.append(result("inventory." + kind, kind, "unsafe", key))
                    dirs.remove(name)
            for name in sorted(names):
                key = (Path(directory) / name).relative_to(path).as_posix()
                if not safe_key(key):
                    results.append(result("inventory." + kind, kind, "unsafe", key))
                    continue
                try:
                    files[key] = hash_file(path, key)
                except (OSError, CheckError) as exc:
                    results.append(result("inventory." + kind, kind, file_error(exc), key))
        return path, files
    except (OSError, CheckError):
        results.append(result("inventory." + kind, kind, "unsafe"))
        return None, files


def inventory_report(files):
    entries = sorted(({"key_sha256": key_hash(key), **metadata}
                      for key, metadata in files.items()), key=lambda row: row["key_sha256"])
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    return {"files": len(entries), "total_bytes": sum(e["bytes"] for e in entries),
            "inventory_sha256": hashlib.sha256(encoded).hexdigest(), "entries": entries}


def load_manifest(path, results):
    if path is None:
        return None
    try:
        document = read_json(path)
        if not isinstance(document, dict) or not isinstance(document.get("files"), list):
            raise CheckError("manifest_invalid")
        files = {}
        for row in document["files"]:
            if not isinstance(row, dict):
                raise CheckError("manifest_invalid")
            key = row.get("relative_path")
            if (not safe_key(key) or key in files or type(row.get("bytes")) is not int
                    or row["bytes"] < 0 or not isinstance(row.get("sha256"), str)
                    or not SHA256.fullmatch(row["sha256"])):
                raise CheckError("manifest_invalid")
            files[key] = {"bytes": row["bytes"], "sha256": row["sha256"].lower()}
        return files
    except (OSError, ValueError, CheckError):
        results.append(result("input.manifest", "download", "manifest_invalid"))
        return None


def check(references, s3_root=None, merch_root=None, downloads_manifest=None):
    results, inventories, roots, files = [], {}, {}, {}
    for kind, root in (("s3", s3_root), ("merch", merch_root)):
        if root is not None:
            roots[kind], files[kind] = inventory_root(root, kind, results)
            inventories[kind] = {"status": "locally_inventoried", **inventory_report(files[kind])}
    manifest = load_manifest(downloads_manifest, results)
    if manifest is not None:
        inventories["download"] = {"status": "source_manifest_only_not_restored",
                                    **inventory_report(manifest)}
    if not isinstance(references, list):
        results.append(result("input.references", "invalid_kind", "invalid_input"))
        references = []
    for ref in references:
        source = ref.get("source") if isinstance(ref, dict) else None
        kind = ref.get("kind") if isinstance(ref, dict) else None
        if (not isinstance(source, str) or len(source) > MAX_SOURCE_LENGTH
                or not SOURCE.fullmatch(source)
                or not isinstance(kind, str) or kind not in KINDS
                or set(ref) - {"source", "kind", "key", "expected_bytes", "expected_sha256"}):
            results.append(result("input.references", "invalid_kind", "invalid_reference"))
            continue
        key = ref.get("key")
        if not expected_fields_valid(ref):
            results.append(result(source, kind, "invalid_reference"))
            continue
        if kind in {"generated", "external", "terminal_local"}:
            results.append(result(source, kind, "unchecked_" + kind))
            continue
        if not safe_key(key):
            results.append(result(source, kind, "unsafe", key if isinstance(key, str) else None))
            continue
        if kind == "download":
            metadata = manifest.get(key) if manifest is not None else None
            absent = "manifest_required" if manifest is None else "missing"
        else:
            if roots.get(kind) is None:
                results.append(result(source, kind, "root_required", key))
                continue
            try:
                # Reopen safely so even a replacement since inventory cannot redirect reads.
                metadata = hash_file(roots[kind], key)
                absent = "missing"
            except (OSError, CheckError) as exc:
                results.append(result(source, kind, file_error(exc), key))
                continue
        if metadata is None:
            results.append(result(source, kind, absent, key))
        elif ref.get("expected_bytes") is not None and ref["expected_bytes"] != metadata["bytes"]:
            results.append(result(source, kind, "size_mismatch", key))
        elif ref.get("expected_sha256") is not None and ref["expected_sha256"].lower() != metadata["sha256"]:
            results.append(result(source, kind, "sha256_mismatch", key))
        else:
            status = "source_verified_not_restored" if kind == "download" else "restored_present"
            results.append(result(source, kind, status))
    by_source = defaultdict(Counter)
    for row in results:
        by_source[row["source"]][row["status"]] += 1
    grouped = Counter((r["source"], r["kind"], r["status"]) for r in results)
    return {"schema_version": 1, "ok": not any(r["status"] in FAILURES for r in results),
            "counts": {"by_kind": dict(Counter(r["kind"] for r in results)),
                       "by_status": dict(Counter(r["status"] for r in results)),
                       "by_source": {key: dict(value) for key, value in sorted(by_source.items())},
                       "by_source_kind_status": [{"source": source, "kind": kind,
                                                   "status": status, "count": count}
                                                  for (source, kind, status), count
                                                  in sorted(grouped.items())]},
            "inventories": inventories, "results": results}


def stdout_summary(report):
    counts = report["counts"]
    sources = list(counts["by_source"].items())
    groups = counts["by_source_kind_status"]
    failures = [r for r in report["results"] if r["status"] in FAILURES]
    unique = Counter(tuple(sorted(row.items())) for row in failures)
    preview = [{**dict(row), "count": count}
               for row, count in list(unique.items())[:PREVIEW_LIMIT]]
    return {
        "schema_version": report["schema_version"], "ok": report["ok"],
        "counts": {**counts,
                   "by_source": dict(sources[:PREVIEW_LIMIT]),
                   "by_source_truncated_count": max(0, len(sources) - PREVIEW_LIMIT),
                   "by_source_kind_status": groups[:PREVIEW_LIMIT],
                   "by_source_kind_status_truncated_count": max(0, len(groups) - PREVIEW_LIMIT)},
        "inventories": {kind: {k: v for k, v in inventory.items() if k != "entries"}
                        for kind, inventory in report["inventories"].items()},
        "failure_total": len(failures), "failure_unique_total": len(unique),
        "failures": preview,
        # Counts omitted unique failures, rather than hiding repeated occurrences.
        "truncated_count": max(0, len(unique) - PREVIEW_LIMIT),
    }


class Parser(argparse.ArgumentParser):
    def error(self, _message):
        raise CheckError("invalid_arguments")


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("--references", required=True, help="private normalized JSON array")
    parser.add_argument("--s3-root")
    parser.add_argument("--merch-root")
    parser.add_argument("--downloads-manifest")
    parser.add_argument("--output", help="new local report file outside input roots")
    try:
        args = parser.parse_args(argv)
        report = check(read_json(args.references), args.s3_root, args.merch_root,
                       args.downloads_manifest)
        if args.output:
            output = Path(args.output)
            candidate = output.parent.resolve(strict=True) / output.name
            protected = [Path(p).resolve() for p in
                         (args.references, args.downloads_manifest) if p]
            roots = [Path(p).resolve() for p in (args.s3_root, args.merch_root) if p]
            if (candidate in protected or any(candidate == r or r in candidate.parents for r in roots)
                    or "://" in args.output):
                raise CheckError("output_error")
            # New-file-only avoids overwriting an input, symlink or an unrelated local file.
            with output.open("x", encoding="utf-8") as stream:
                json.dump(report, stream, sort_keys=True, ensure_ascii=True, indent=2)
                stream.write("\n")
    except (OSError, ValueError, CheckError):
        print(json.dumps({"ok": False, "error": "invalid_input_or_output"}))
        return 2
    print(json.dumps(stdout_summary(report), sort_keys=True, ensure_ascii=True))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
