#!/usr/bin/env python3
"""Verify a docker-save archive represents the exact runnable image under a tag.

Docker's containerd image store can report an OCI *manifest* digest as `.Id`,
while `docker load` on another engine reports the config digest. Comparing IDs
across those formats incorrectly rejects a byte-for-byte valid rollback image.
We therefore compare rootfs diff IDs and every relevant runtime config field,
and validate the archive's config digest against its own manifest.
"""

import hashlib
import json
import subprocess
import sys
import tarfile
import zlib
from pathlib import Path


def fail() -> None:
    raise SystemExit("ERROR: image archive does not match the inspected image")


def runtime_config(config: dict) -> dict:
    list_fields = ("Env", "Entrypoint", "Cmd", "Shell")
    map_fields = ("Labels", "Healthcheck")
    values = {name: list(config.get(name) or []) for name in list_fields}
    values.update({name: config.get(name) or {} for name in map_fields})
    values.update({name: config.get(name) or "" for name in ("User", "WorkingDir", "StopSignal")})
    values["ExposedPorts"] = sorted((config.get("ExposedPorts") or {}).keys())
    values["Volumes"] = sorted((config.get("Volumes") or {}).keys())
    return values


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify-image-archive.py <docker-save.tar.gz> <image-tag>")
    archive_path, image_tag = Path(sys.argv[1]), sys.argv[2]
    if not archive_path.is_file() or not image_tag.startswith("epharm/"):
        fail()

    try:
        with tarfile.open(archive_path, "r:gz") as archive:
            manifest_member = archive.extractfile("manifest.json")
            if manifest_member is None:
                fail()
            manifest = json.load(manifest_member)
            if len(manifest) != 1 or manifest[0].get("RepoTags") != [image_tag]:
                fail()
            entry = manifest[0]
            config_name = entry.get("Config", "")
            if not isinstance(config_name, str) or not config_name.startswith("blobs/sha256/"):
                fail()
            config_member = archive.extractfile(config_name)
            if config_member is None:
                fail()
            config_bytes = config_member.read()
            config_digest = hashlib.sha256(config_bytes).hexdigest()
            if config_name != f"blobs/sha256/{config_digest}":
                fail()
            saved = json.loads(config_bytes)
            layers = entry.get("Layers", [])
            if not isinstance(layers, list) or not layers:
                fail()
            diff_ids = saved.get("rootfs", {}).get("diff_ids", [])
            if len(layers) != len(diff_ids):
                fail()
            for layer, diff_id in zip(layers, diff_ids):
                if not isinstance(layer, str) or not layer.startswith("blobs/sha256/"):
                    fail()
                member = archive.extractfile(layer)
                if member is None:
                    fail()
                first = member.read(1024 * 1024)
                zipped = first.startswith(b"\x1f\x8b")
                decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS) if zipped else None
                compressed_hash = hashlib.sha256()
                uncompressed_hash = hashlib.sha256()
                block = first
                while block:
                    compressed_hash.update(block)
                    uncompressed_hash.update(decompressor.decompress(block) if decompressor else block)
                    block = member.read(1024 * 1024)
                if decompressor:
                    uncompressed_hash.update(decompressor.flush())
                    if not decompressor.eof:
                        fail()
                if compressed_hash.hexdigest() != layer.rsplit("/", 1)[-1]:
                    fail()
                if f"sha256:{uncompressed_hash.hexdigest()}" != diff_id:
                    fail()
    except (OSError, KeyError, ValueError, tarfile.TarError, UnicodeDecodeError, zlib.error):
        fail()

    inspected = subprocess.run(
        ["docker", "image", "inspect", "--format", "{{json .}}", image_tag],
        capture_output=True,
        text=True,
        check=False,
    )
    if inspected.returncode != 0:
        fail()
    try:
        live = json.loads(inspected.stdout)
    except ValueError:
        fail()
    if (
        saved.get("architecture") != live.get("Architecture")
        or saved.get("os") != live.get("Os")
        or (saved.get("variant") or "") != (live.get("Variant") or "")
        or saved.get("rootfs", {}).get("diff_ids") != live.get("RootFS", {}).get("Layers")
        or runtime_config(saved.get("config", {})) != runtime_config(live.get("Config", {}))
    ):
        fail()
    print(f"sha256:{config_digest}")


if __name__ == "__main__":
    main()
