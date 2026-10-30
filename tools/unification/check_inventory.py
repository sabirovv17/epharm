#!/usr/bin/env python3
"""Check source inventories and POSM contracts; this is not runtime acceptance."""

import argparse
import json
import re
import sys
from pathlib import Path, PurePosixPath

from check_api_coverage import validate_api_coverage


STATES = {"source_confirmed", "placeholder", "unwired", "needs_verification"}
GROUPS = {f"{prefix}{number:02}" for prefix, end in (("H", 17), ("L", 20), ("M", 20))
          for number in range(1, end + 1)}
ORIGIN = "https://epharm.inkar.kz"
METHODS = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}
SOURCE_KINDS = ("ui", "api", "jobs", "files", "data")
CONTROL_CHARACTERS = re.compile(r"[\x00-\x1f\x7f-\x9f]")
FILE_SUFFIXES = {".py", ".sh", ".cs", ".xaml", ".kt", ".js", ".jsx", ".ts", ".tsx",
                 ".sql", ".json", ".yml", ".yaml", ".md", ".patch", ".service", ".timer",
                 ".css", ".html", ".xml", ".conf", ".png", ".jpg", ".pdf", ".mp4"}


def text(value):
    return isinstance(value, str) and bool(value.strip())


def string_list(value):
    return isinstance(value, list) and bool(value) and all(text(item) for item in value)


def source_path(value, label, errors, root=None, required=False):
    """Recognize physical references; SQL/functions/API and artifact descriptions are labels."""
    if CONTROL_CHARACTERS.search(value):
        errors.append(f"{label}: source paths cannot contain control characters: {value!r}")
        return
    if re.match(r"^(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS) /", value) or value.startswith("SQL "):
        if required:
            errors.append(f"{label}: expected a repository-relative source path")
        return
    path = value.split("#", 1)[0].split(";", 1)[0].strip()
    if re.match(r"^[A-Za-z]:", path) or "\\" in path or "://" in path:
        errors.append(f"{label}: source path must be repository-relative POSIX: {value!r}")
        return
    path = path.split(":", 1)[0]
    physical = (path.startswith(("/", "../")) or
                PurePosixPath(path).suffix in FILE_SUFFIXES or
                path in {"Caddyfile", "Dockerfile", "Makefile"} or
                ("/" in path and not re.search(r"\s", path)))
    if not physical:
        if required:
            errors.append(f"{label}: expected a repository-relative source path: {value!r}")
        return
    if PurePosixPath(path).is_absolute() or ".." in PurePosixPath(path).parts:
        errors.append(f"{label}: absolute paths and '..' traversal are forbidden: {value!r}")
        return
    if root is not None:
        try:
            candidate = (root / path).resolve()
            if not candidate.is_relative_to(root.resolve()):
                errors.append(f"{label}: source path escapes repository root: {value!r}")
            elif not candidate.exists():
                errors.append(f"{label}: source path does not exist under {root}: {path}")
        except (OSError, ValueError, RuntimeError) as error:
            errors.append(f"{label}: cannot resolve source path {value!r}: {error}")


def validate_inventory(items, errors, epharm_root, merch_root):
    seen, groups = set(), set()
    for index, item in enumerate(items):
        label = f"inventory[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{label}: expected an object")
            continue
        item_id, group = item.get("id"), item.get("group")
        label = f"inventory[{item_id}]" if text(item_id) else label
        if not text(item_id) or not re.fullmatch(r"[HLM][0-9]{2}\.[0-9]{2,}", item_id):
            errors.append(f"{label}.id: expected stable group.action ID, e.g. H01.01")
        else:
            if item_id in seen:
                errors.append(f"{label}.id: duplicate ID {item_id}")
            seen.add(item_id)
            if item_id.split(".")[0] != group or int(item_id.split(".")[1]) == 0:
                errors.append(f"{label}.id: ID prefix must match group and action number must be positive")
        if not isinstance(group, str) or group not in GROUPS:
            errors.append(f"{label}.group: expected H01..H17, L01..L20 or M01..M20")
        else:
            groups.add(group)
        if not text(item.get("name")):
            errors.append(f"{label}.name: expected non-empty text")
        if not isinstance(item.get("state"), str) or item["state"] not in STATES:
            errors.append(f"{label}.state: expected one of {', '.join(sorted(STATES))}; done/live_ready are not source states")
        if not string_list(item.get("acceptance")):
            errors.append(f"{label}.acceptance: expected a non-empty list of acceptance checks")
        source = item.get("source")
        if not isinstance(source, dict):
            errors.append(f"{label}.source: expected an object")
        else:
            for key in ("repo", "revision"):
                if not text(source.get(key)):
                    errors.append(f"{label}.source.{key}: expected non-empty text")
            if not any(string_list(source.get(key)) for key in SOURCE_KINDS):
                errors.append(f"{label}.source: at least one of ui/api/jobs/files/data must have references")
            repo = source.get("repo", "")
            repo = repo.rstrip("/").split("/")[-1].removesuffix(".git") if isinstance(repo, str) else ""
            root = {"epharm": epharm_root, "pharmacy-crm-demo": merch_root}.get(repo)
            if (epharm_root or merch_root) and repo not in {"epharm", "pharmacy-crm-demo"}:
                errors.append(f"{label}.source.repo: no root mapping for {source.get('repo')!r}")
            for key in SOURCE_KINDS:
                values = source.get(key, [])
                if not isinstance(values, list) or not all(text(value) for value in values):
                    errors.append(f"{label}.source.{key}: expected a list of non-empty references")
                    continue
                if key in ("ui", "jobs", "files"):
                    for value in values:
                        source_path(value, f"{label}.source.{key}", errors, root)
            if "runtimeFiles" in source:
                values = source["runtimeFiles"]
                if not string_list(values):
                    errors.append(f"{label}.source.runtimeFiles: expected a non-empty list of relative paths")
                else:
                    for value in values:
                        if CONTROL_CHARACTERS.search(value):
                            errors.append(f"{label}.source.runtimeFiles: paths cannot contain control characters: {value!r}")
                        elif (value.strip() != value or value == "." or "\\" in value or
                                re.match(r"^[A-Za-z]:", value) or "://" in value or
                                PurePosixPath(value).is_absolute() or ".." in PurePosixPath(value).parts):
                            errors.append(f"{label}.source.runtimeFiles: expected POSIX relative path without traversal: {value!r}")
        target = item.get("target")
        if not isinstance(target, dict):
            errors.append(f"{label}.target: expected an object")
        else:
            for key in ("section", "subsection", "scope"):
                if not text(target.get(key)):
                    errors.append(f"{label}.target.{key}: expected non-empty text")
            if not string_list(target.get("actions")):
                errors.append(f"{label}.target.actions: expected a non-empty list")
    missing = GROUPS - groups
    if missing:
        errors.append("inventory: missing groups: " + ", ".join(sorted(missing)))


def validate_contracts(document, errors, epharm_root):
    if not isinstance(document, dict):
        errors.append("contracts: expected an object with scope and contracts")
        return 0
    scope = document.get("scope")
    if not isinstance(scope, dict) or scope.get("publicOrigin") != ORIGIN:
        errors.append(f"contracts.scope.publicOrigin: must be exactly {ORIGIN}")
    if not isinstance(scope, dict) or scope.get("mandatoryRpsWindowAllowed") is not False:
        errors.append("contracts.scope.mandatoryRpsWindowAllowed: must be false")
    contracts = document.get("contracts")
    if not isinstance(contracts, list) or not contracts:
        errors.append("contracts.contracts: expected a non-empty list")
        return 0
    seen = set()
    required = {"method", "path", "auth", "identity", "request", "response", "errors",
                "sideEffects", "requiredInvariant", "sourcePaths", "positiveChecks", "negativeChecks", "state"}
    for index, contract in enumerate(contracts):
        label = f"contracts[{index}]"
        if not isinstance(contract, dict):
            errors.append(f"{label}: expected an object")
            continue
        item_id = contract.get("id")
        if not text(item_id):
            errors.append(f"{label}.id: expected a non-empty stable ID")
        else:
            label = f"contracts[{item_id}]"
            if item_id in seen:
                errors.append(f"{label}.id: duplicate ID {item_id}")
            seen.add(item_id)
        for key in sorted(required - contract.keys()):
            errors.append(f"{label}.{key}: required field is missing")
        method = contract.get("method")
        if not isinstance(method, str) or method not in METHODS:
            errors.append(f"{label}.method: expected an uppercase HTTP method")
        path = contract.get("path")
        if not text(path) or not path.startswith("/") or path.startswith("//") or ".." in path.split("/"):
            errors.append(f"{label}.path: expected an absolute HTTP route without traversal")
        if "origin" in contract and contract["origin"] != ORIGIN:
            errors.append(f"{label}.origin: must be exactly {ORIGIN}")
        for key in ("auth", "request", "response"):
            if not isinstance(contract.get(key), dict) or not contract[key]:
                errors.append(f"{label}.{key}: expected a non-empty object")
        if not text(contract.get("identity")):
            errors.append(f"{label}.identity: expected non-empty text")
        for key in ("errors", "sideEffects"):
            if not isinstance(contract.get(key), list):
                errors.append(f"{label}.{key}: expected a list (may be empty)")
        for key in ("requiredInvariant", "positiveChecks", "negativeChecks"):
            if not string_list(contract.get(key)):
                errors.append(f"{label}.{key}: expected a non-empty list of checks/invariants")
        if not isinstance(contract.get("state"), str) or contract["state"] not in STATES:
            errors.append(f"{label}.state: expected one of {', '.join(sorted(STATES))}; done/live_ready are not source states")
        sources = contract.get("sourcePaths")
        if not isinstance(sources, list) or not sources:
            errors.append(f"{label}.sourcePaths: expected a non-empty list")
        else:
            for source in sources:
                value = source.get("path") if isinstance(source, dict) else source
                if not text(value):
                    errors.append(f"{label}.sourcePaths: expected source path text or an object with path")
                else:
                    source_path(value, f"{label}.sourcePaths", errors, epharm_root, required=True)
    return len(contracts)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, nargs="+", required=True, help="one merged array or multiple inventory arrays")
    parser.add_argument("--contracts", type=Path, required=True)
    parser.add_argument("--epharm-root", type=Path)
    parser.add_argument("--merch-root", type=Path)
    args = parser.parse_args(argv)
    errors, items = [], []
    for root in (args.epharm_root, args.merch_root):
        if root is not None and not root.is_dir():
            errors.append(f"source root is not a directory: {root}")
    try:
        for path in args.inventory:
            document = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(document, list):
                errors.append(f"{path}: inventory must be a JSON array")
            else:
                items.extend(document)
        contracts = json.loads(args.contracts.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"ERROR: cannot read JSON: {error}", file=sys.stderr)
        return 1
    validate_inventory(items, errors, args.epharm_root, args.merch_root)
    count = validate_contracts(contracts, errors, args.epharm_root)
    endpoints = validate_api_coverage(items, args.epharm_root, errors) if args.epharm_root else None
    if errors:
        print("\n".join(f"ERROR: {error}" for error in errors), file=sys.stderr)
        return 1
    coverage = f", {endpoints} backend endpoints covered" if endpoints is not None else ""
    print(f"Source inventory guard passed: {len(items)} actions, {len(GROUPS)} groups, {count} contracts{coverage}. Runtime behavior was not checked.")
    if any(isinstance(item, dict) and isinstance(item.get("source"), dict) and
           item["source"].get("runtimeFiles") for item in items):
        print("runtimeFiles are pending preservation references; their existence, copying and restoration were not verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
