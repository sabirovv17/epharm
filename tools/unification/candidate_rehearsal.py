#!/usr/bin/env python3
"""Create one host-bound, isolated PG16 snapshot candidate. Never starts application jobs or upgrades public DDL."""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

import crm_stage as stage


def checked(command):
    result = subprocess.run(command, capture_output=True, text=True)
    stage.require(result.returncode == 0, "isolated Docker operation failed; details suppressed")
    return result.stdout


def location(private_root, run_name, container, database):
    root = private_root.resolve(strict=True)
    stage.require(root.is_dir() and root.stat().st_uid == os.getuid() and root.stat().st_mode & 0o077 == 0,
                  "private parent must be owned by the caller and inaccessible to other users")
    stage.require(re.fullmatch(r"candidate-[a-z0-9-]+", run_name), "invalid dedicated candidate run name")
    stage.require(re.fullmatch(r"epharm-unify-candidate-[a-z0-9-]+", container), "invalid dedicated candidate container name")
    stage.require(re.fullmatch(r"epharm_[a-z0-9_]*candidate[a-z0-9_]*", database), "a dedicated candidate database is required")
    run = root / run_name
    stage.require(not run.exists() and not run.is_symlink(), "candidate run path already exists; nothing will be replaced")
    stage.require("," not in str(run), "Docker bind path cannot contain commas")
    return run


def host_capacity(root, source_bytes):
    stage.require(type(source_bytes) is int and source_bytes > 0, "invalid source database footprint")
    stats = os.statvfs(root)
    free = stats.f_bavail * stats.f_frsize
    required = 3 * source_bytes + stage.MIN_HEADROOM
    stage.require(free >= required, "host capacity guard failed: need 3x source footprint plus 1 GiB")
    return free, required


def parse_archive_toc(text):
    original = re.search(r"Dumped from database version: (\d+)\.(\d+)", text)
    dumper = re.search(r"Dumped by pg_dump version: (\d+)\.(\d+)", text)
    stage.require(original and dumper and original.group(1) == dumper.group(1) == "16",
                  "only a verified PG16 -> PG16 backup restore is supported")
    stage.require("Format: CUSTOM" in text, "backup must be a custom PostgreSQL archive")
    return {"databaseVersion": original.group(0).split(": ")[1], "dumpVersion": dumper.group(0).split(": ")[1],
            "tableDataEntries": sum(" TABLE DATA " in line for line in text.splitlines())}


def archive_info(source_container, backup):
    # --list reads the TOC only. The producer may receive SIGPIPE after that metadata has been read.
    producer = subprocess.Popen(["gzip", "-dc", str(backup)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        consumer = subprocess.Popen(["docker", "exec", "-i", source_container, "pg_restore", "--list"],
                                    stdin=producer.stdout, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        producer.stdout.close()
        out, _err = consumer.communicate()
        code = producer.wait()
        stage.require(consumer.returncode == 0 and code in (0, -13, 141), "cannot inspect the verified backup TOC")
    finally:
        if producer.poll() is None:
            producer.terminate()
            producer.wait()
    return parse_archive_toc(out.decode())


def original_facts(db, metadata):
    selects = []
    for table in metadata["tables"]:
        row_hash = stage.hash_expression([c["name"] for c in table["columns"]])
        key_hash = stage.hash_expression(stage.primary_columns(table))
        digest = "encode(pg_catalog.sha256(convert_to(coalesce(string_agg(checks.VALUE||E'\\n','' ORDER BY checks.VALUE COLLATE pg_catalog.\"C\"),''),'UTF8')),'hex')"
        selects.append("SELECT json_build_object('table'," + stage.literal(table["name"]) + ",'rowCount',count(*),'rowSha256',"
                       + digest.replace("VALUE", "rh") + ",'primaryKeySha256'," + digest.replace("VALUE", "kh")
                       + ") FROM (SELECT " + row_hash + " AS rh," + key_hash + " AS kh FROM public." + stage.qi(table["name"]) + ") checks;")
    return [stage.parse_json(line) for line in db.readonly("\n".join(selects)).splitlines()]


def environment(path, user, database):
    password = secrets.token_urlsafe(48)
    stage.private_write(path, ("POSTGRES_USER=" + user + "\nPOSTGRES_DB=" + database + "\nPOSTGRES_PASSWORD=" + password
                               + "\nPGDATA=/var/lib/postgresql/data\n").encode())


def run_command(image, container, env, data):
    return ["docker", "run", "--detach", "--pull=never", "--network=none", "--name", container,
            "--env-file", str(env), "--mount", "type=bind,source=" + str(data) + ",target=/var/lib/postgresql/data", image]


def restore_backup(container, user, database, backup, log):
    stage.private_write(log, b"")
    with log.open("ab") as private_log:
        producer = subprocess.Popen(["gzip", "-dc", str(backup)], stdout=subprocess.PIPE, stderr=private_log)
        try:
            consumer = subprocess.Popen(["docker", "exec", "-i", container, "pg_restore", "--username=" + user,
                                         "--dbname=" + database, "--single-transaction", "--exit-on-error", "--no-owner", "--no-privileges"],
                                        stdin=producer.stdout, stdout=private_log, stderr=private_log)
            producer.stdout.close()
            target_code = consumer.wait()
            source_code = producer.wait()
            stage.require(target_code == source_code == 0, "same-major restore failed; private log retained, nothing deleted")
        finally:
            if producer.poll() is None:
                producer.terminate()
                producer.wait()


def wait_ready(container, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = subprocess.run(["docker", "exec", container, "pg_isready", "-h", "127.0.0.1"], capture_output=True)
        if result.returncode == 0:
            return
        time.sleep(0.25)
    raise stage.StageError("new candidate did not become ready; container and private data retained")


def progress(phase):
    print(json.dumps({"phase": phase}), flush=True)


def create(args):
    run = location(args.private_root, args.run_name, args.container, args.database)
    stage.require(re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", args.user), "invalid candidate database user")
    stage.require(re.fullmatch(r"sha256:[0-9a-f]{64}", args.image_id), "cached image ID must be pinned")
    image = stage.parse_json(checked(["docker", "image", "inspect", args.image_id, "--format", "{{json .Id}}"] ))
    stage.require(image == args.image_id, "cached image pin differs")
    existing = checked(["docker", "container", "ls", "--all", "--filter", "name=^" + args.container + "$", "--format", "{{.Names}}"])
    stage.require(not existing.strip(), "container name already exists; unknown containers are never reused or deleted")
    stage.require(not args.report.exists() and not args.report.is_symlink(), "report already exists; refusing replacement")
    stage.require(stage.file_hash(args.backup) == stage.hash_arg(args.expected_backup_sha256), "PG16 backup checksum differs")
    stage.require(stage.file_hash(args.crm_artifact) == stage.hash_arg(args.expected_crm_sha256), "CRM artifact checksum differs")
    source = stage.Database(args.source_container, args.user, args.source_database)
    source_info = source.inspect()
    stage.require(source_info["identitySha256"] == stage.hash_arg(args.expected_source_identity_sha256), "original source identity differs")
    stage.require(source_info["versionNum"] // 10000 == 16, "original source must be PostgreSQL16")
    source_image = stage.parse_json(checked(["docker", "inspect", args.source_container, "--format", "{{json .Image}}"] ))
    stage.require(source_image == args.image_id, "candidate must reuse the exact verified source PG16 image")
    metadata = stage.parse_json(source.readonly(stage.metadata_sql()))
    stage.require(len(metadata["tables"]) == 73, "expected all 73 original source tables")
    toc = archive_info(args.source_container, args.backup)
    stage.require(toc["tableDataEntries"] == 73, "PG16 backup table-data inventory differs")
    source_bytes = metadata["database"]["sizeBytes"]
    host_free, required = host_capacity(run.parent, source_bytes)
    progress("read_only_original_column_and_primary_key_hashes")
    source_checksums = original_facts(source, metadata)
    stage.require(len(source_checksums) == 73, "original source checksum inventory differs")
    # Everything above is read-only. New paths/objects below are never reused or removed.
    run.mkdir(mode=0o700)
    data, env = run / "pgdata", run / "postgres.env"
    data.mkdir(mode=0o700)
    environment(env, args.user, args.database)
    stage.private_write(run / "original-source-checksums.json", stage.encoded(source_checksums))
    progress("create_new_host_bound_candidate")
    container_id = checked(run_command(args.image_id, args.container, env, data)).strip()
    stage.private_write(run / "created-container.json", stage.encoded({"container": args.container, "id": container_id,
                        "imageId": args.image_id, "dataDirectory": str(data), "database": args.database}))
    wait_ready(args.container)
    mounts = stage.parse_json(checked(["docker", "inspect", args.container, "--format", "{{json .Mounts}}"] ))
    allowed_mounts = {str(data), "/host_mnt" + str(data), "/run/desktop/mnt/host" + str(data)}
    stage.require(len(mounts) == 1 and mounts[0]["Type"] == "bind" and mounts[0]["Source"] in allowed_mounts
                  and mounts[0]["Destination"] == "/var/lib/postgresql/data", "candidate PGDATA is not the approved host bind")
    target = stage.Database(args.container, args.user, args.database)
    target_info = target.inspect()
    stage.require(target_info["versionNum"] // 10000 == 16, "candidate image is not PostgreSQL16")
    progress("same_major_pg16_restore_no_application_start")
    restore_backup(args.container, args.user, args.database, args.backup, run / "restore.log")
    target_metadata = stage.parse_json(target.readonly(stage.metadata_sql()))
    target_checksums = original_facts(target, target_metadata)
    stage.require(stage.schema_hash(metadata) == stage.schema_hash(target_metadata), "restored original public catalogue differs")
    stage.require(source_checksums == target_checksums, "restored original columns/primary keys/counts differ")
    stage.require(stage.file_hash(args.backup) == args.expected_backup_sha256, "original backup changed during rehearsal")
    progress("load_same_typed_crm_snapshot")
    typed = stage.load_artifact(target, args.crm_artifact, args.expected_crm_sha256, target_info["identitySha256"], 57,
                                stage.MIN_HEADROOM, run / "crm-import.json")
    replay = stage.load_artifact(target, args.crm_artifact, args.expected_crm_sha256, target_info["identitySha256"], 57,
                                 stage.MIN_HEADROOM, run / "crm-replay.json")
    stage.require(typed["tableChecksums"] == replay["tableChecksums"], "CRM replay checksum evidence differs")
    stage.require(original_facts(target, target_metadata) == source_checksums, "CRM intake changed original public rows")
    stage.require(original_facts(source, metadata) == source_checksums, "original source changed during rehearsal")
    stage.require(stage.schema_hash(stage.parse_json(source.readonly(stage.metadata_sql()))) == stage.schema_hash(metadata),
                  "original source catalogue changed during rehearsal")
    stage.require(stage.schema_hash(stage.parse_json(target.readonly(stage.metadata_sql()))) == stage.schema_hash(metadata),
                  "original candidate public catalogue changed during CRM intake")
    after = target.inspect()
    manifest = {"container": args.container, "containerId": container_id, "imageId": args.image_id,
                "database": args.database, "dataDirectory": str(data), "environmentFile": str(env),
                "targetIdentitySha256": after["identitySha256"], "sourceIdentitySha256": source_info["identitySha256"],
                "originalBackupSha256": args.expected_backup_sha256, "crmArtifactSha256": args.expected_crm_sha256,
                "originalChecksums": source_checksums, "crmChecksums": typed["tableChecksums"]}
    stage.private_write(run / "rehearsal-manifest.json", stage.encoded(manifest))
    summary = {"state": "source_confirmed", "functionalMergeAccepted": False, "publicUpgradeApplied": False,
               "imageId": args.image_id, "originalBackupSha256": args.expected_backup_sha256,
               "sourceIdentitySha256": source_info["identitySha256"], "targetIdentitySha256": after["identitySha256"],
               "sourcePublicSchemaSha256": stage.schema_hash(metadata), "restoredPublicSchemaSha256": stage.schema_hash(target_metadata),
               "originalTables": 73, "originalRows": sum(t["rowCount"] for t in source_checksums),
               "originalTableChecksums": source_checksums, "originalSourceUnchanged": True,
               "originalPublicUnchangedAfterCrmIntake": True, "crmTables": typed["tables"], "crmRows": typed["rows"],
               "crmArtifactSha256": args.expected_crm_sha256, "crmTableChecksums": typed["tableChecksums"],
               "crmReplayMode": replay["mode"], "totalSourceTables": 73 + typed["tables"],
               "pgdataMountType": "private_host_bind", "hostFreeBytesBefore": host_free,
               "requiredHostFreeBytes": required, "sourceDatabaseBytes": source_bytes,
               "targetFilesystemFreeBytesAfter": after["freeBytes"],
               "targetDatabaseBytes": stage.parse_json(target.readonly("SELECT json_build_object('bytes',pg_database_size(current_database()));"))["bytes"],
               "boundary": "One isolated PG16 snapshot candidate retains all original public rows and inert typed CRM rows. No source writes, application start, Flyway66/67 upgrade, functional port, API/reward replay, global Docker cleanup or production change."}
    stage.private_write(args.report, (json.dumps(summary, sort_keys=True, indent=2) + "\n").encode())
    return {"report": str(args.report), "privateManifest": str(run / "rehearsal-manifest.json"),
            "environmentFile": str(env), "targetIdentitySha256": after["identitySha256"],
            "originalTables": 73, "crmTables": typed["tables"], "totalSourceRows": summary["originalRows"] + summary["crmRows"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["create"])
    for key in ("private-root", "backup", "crm-artifact", "report"):
        parser.add_argument("--" + key, required=True, type=Path)
    for key in ("run-name", "container", "database", "user", "image-id", "expected-backup-sha256", "source-container",
                "source-database", "expected-source-identity-sha256", "expected-crm-sha256"):
        parser.add_argument("--" + key, required=True)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(create(args), sort_keys=True))
        return 0
    except stage.StageError as error:
        print("Candidate rehearsal: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, RuntimeError):
        print("Candidate rehearsal failed; private artifacts retained; no credential/data values logged and nothing deleted.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
