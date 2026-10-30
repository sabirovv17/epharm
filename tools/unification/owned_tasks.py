#!/usr/bin/env python3
"""Preserve CRM task projections in an isolated candidate; never replay a worker or credit."""

import argparse
import json
import sys
from pathlib import Path

import crm_stage as stage
import owned_merch as owned

SOURCE_TABLES = (
    "staff_task_dispatches", "staff_tasks", "staff_task_checklist_items",
    "staff_task_attachments", "staff_task_events", "staff_task_dispatch_links",
    "staff_task_delivery_receipts",
)
TARGET_TABLES = (
    "merch_task_dispatches", "merch_task_items", "merch_task_evidence",
    "merch_task_events", "merch_task_links", "merch_task_delivery_receipts",
)


def projections(schema):
    stage.require(schema.startswith("crm_stage_") and len(schema) == 26 and
                  all(c in "0123456789abcdef" for c in schema[10:]), "invalid staging schema")
    q = stage.qi(schema)
    origin = stage.literal(owned.ORIGIN)
    checklist = ("COALESCE((SELECT jsonb_agg(jsonb_build_object('id',c.id,'title',c.title,'required',c.required) "
                 "ORDER BY c.position,c.id) FROM " + q + ".staff_task_checklist_items c WHERE c.task_id=s.id),'[]'::jsonb)")
    raw_checklist = ("COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.position,c.id) FROM " + q +
                     ".staff_task_checklist_items c WHERE c.task_id=s.id),'[]'::jsonb)")
    return [
        ("merch_task_dispatches", "staff_task_dispatches", {
            "id": "s.id", "source_system": "'MERCH_CRM'", "source_origin": origin,
            "source_id": "s.id", "source_payload": "to_jsonb(s)", "branch_id": "s.branch_id",
            "title": "s.title", "status": "s.status", "priority": "s.priority", "task_type": "s.task_type",
            "due_at": "s.due_at", "reward_amount_kzt": "s.reward_amount_kzt",
            "historical_reward_preserved": "s.completed_at IS NOT NULL OR EXISTS(SELECT 1 FROM " + q + ".epharm_reward_outbox o WHERE o.dispatch_id=s.id)",
            "source_created_by": "s.created_by", "source_mass_owner_id": "s.mass_owner_id",
            "created_at": "s.created_at", "updated_at": "s.updated_at",
        }, ""),
        ("merch_task_items", "staff_tasks", {
            "id": "s.id", "source_id": "s.id", "dispatch_id": "s.dispatch_id",
            "source_payload": "to_jsonb(s)||jsonb_build_object('_unification_checklist_source'," + raw_checklist + ")",
            "title": "s.title", "instructions": "COALESCE(s.description,'')", "task_type": "s.task_type",
            "available_from": "s.available_from", "due_at": "s.due_at", "status": "s.status",
            "required": "true", "required_photo_count": "s.required_photo_count", "checklist": checklist,
            "source_assignee_id": "s.assignee_id", "source_reviewer_id": "s.reviewer_id",
            "created_at": "s.created_at", "updated_at": "s.updated_at",
        }, ""),
        ("merch_task_evidence", "staff_task_attachments", {
            "id": "s.id", "item_id": "s.task_id", "storage_backend": "'legacy_reference'",
            "object_key": "s.object_key", "mime_type": "s.mime_type", "byte_size": "s.byte_size",
            "original_filename": "s.original_filename", "metadata": "s.evidence_metadata||jsonb_build_object('_source',to_jsonb(s))",
            "source_uploaded_by": "s.uploaded_by", "created_at": "s.created_at",
        }, ""),
        ("merch_task_events", "staff_task_events", {
            "id": "s.id", "dispatch_id": "t.dispatch_id", "item_id": "s.task_id", "source_actor_id": "s.actor_id",
            "event_type": "s.event_type", "details": "s.details", "created_at": "s.created_at", "source_payload": "to_jsonb(s)",
        }, " JOIN " + q + ".staff_tasks t ON t.id=s.task_id"),
        ("merch_task_links", "staff_task_dispatch_links", {
            "id": "s.id", "dispatch_id": "s.dispatch_id", "token_hash": "s.token_hash",
            "integration_access_token": "s.integration_access_token", "issued_channel": "s.issued_channel",
            "expires_at": "s.expires_at", "revoked_at": "s.revoked_at", "submitted_at": "s.submitted_at", "source_payload": "to_jsonb(s)",
        }, ""),
        ("merch_task_delivery_receipts", "staff_task_delivery_receipts", {
            "link_id": "s.link_id", "dispatch_id": "s.dispatch_id", "device_id": "s.device_id", "shown_at": "s.shown_at",
        }, ""),
    ]


def import_sql(schema, source_hash, expected, verification):
    key = "owned_merch_tasks:" + source_hash[:24]
    q = stage.qi(schema)
    sql = ["BEGIN ISOLATION LEVEL REPEATABLE READ;", stage.SETTINGS,
           "SELECT pg_advisory_xact_lock(817106,2);", verification,
           owned.assertion("NOT EXISTS(SELECT 1 FROM " + q + ".staff_tasks WHERE dispatch_id IS NULL)", "standalone task requires explicit disposition"),
           "CREATE TEMP TABLE _task_replay AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=" + stage.literal(key) + ") AS done;"]
    for target, source, fields, join in projections(schema):
        target_sql = "public." + stage.qi(target)
        from_sql = q + "." + stage.qi(source) + " s" + join
        sql += [owned.assertion("(SELECT done FROM _task_replay) OR NOT EXISTS(SELECT 1 FROM " + target_sql + ")", "task target is not empty"),
                "INSERT INTO " + target_sql + "(" + ",".join(map(stage.qi, fields)) + ") SELECT " + ",".join(fields.values()) + " FROM " + from_sql + " WHERE NOT(SELECT done FROM _task_replay);",
                owned.assertion("(SELECT count(*) FROM " + target_sql + ")=" + str(expected[source]), "task count differs")]
        keys = ["link_id", "device_id"] if target == "merch_task_delivery_receipts" else ["id"]
        on = " AND ".join("d." + stage.qi(k) + "=s." + stage.qi(k) for k in keys)
        equal = " AND ".join("d." + stage.qi(k) + " IS NOT DISTINCT FROM (" + v + ")" for k, v in fields.items())
        sql.append(owned.assertion("NOT EXISTS(SELECT 1 FROM " + from_sql + " LEFT JOIN " + target_sql + " d ON " + on + " WHERE NOT(" + equal + ") OR d." + stage.qi(keys[0]) + " IS NULL)", "task projection differs"))
    # Source snapshots do not provide a verified PERSON or a proven native attempt boundary.
    sql += [owned.assertion("NOT EXISTS(SELECT 1 FROM public.merch_task_attempts)", "unexpected reconstructed attempt"),
            owned.assertion("NOT EXISTS(SELECT 1 FROM public.merch_task_rewards)", "unexpected reward during task import")]
    result = {"sourceRows": sum(expected.values()), "dispatches": expected["staff_task_dispatches"],
              "tasks": expected["staff_tasks"], "evidence": expected["staff_task_attachments"],
              "checklistItems": expected["staff_task_checklist_items"], "newAttempts": 0, "newRewards": 0,
              "limitations": ["source in-progress/submitted attempts need explicit verified resume/review; no invented executor", "native QR ownership is a separate verified V074 projection", "physical legacy media must be configured from the verified private manifest"]}
    sql += ["INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT " +
            ",".join(map(stage.literal, [key, source_hash, source_hash, json.dumps(result)])) + "::jsonb WHERE NOT(SELECT done FROM _task_replay);",
            "SELECT json_build_object('mode',CASE WHEN (SELECT done FROM _task_replay) THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,'result'," + stage.literal(json.dumps(result)) + "::jsonb);", "COMMIT;"]
    return "\n".join(sql)


def run(args):
    stage.require(not args.report.exists() and not args.report.is_symlink(), "report must be a new path")
    stage.require(args.artifact.stat().st_mode & 0o077 == 0, "source artifact must be private")
    stage.require(args.report.resolve() != args.artifact.resolve(), "report cannot replace input")
    artifact = stage.parse_json(args.artifact.read_bytes())
    source_hash = stage.file_hash(args.artifact)
    stage.require(source_hash == stage.hash_arg(args.artifact_sha256), "source hash mismatch")
    stage.validate_artifact(artifact, 57)
    db = stage.Database(args.container, args.user, args.database)
    stage.require(args.database == "epharm_unified_candidate", "designated candidate only")
    target = db.inspect()
    stage.require(target["identitySha256"] == stage.hash_arg(args.target_sha256), "candidate pin mismatch")
    stage.require(target["versionNum"] // 10000 == 16, "PostgreSQL16 candidate required")
    stage.require(target["freeBytes"] >= stage.MIN_HEADROOM + args.artifact.stat().st_size * 8, "insufficient headroom")
    schema = "crm_stage_" + source_hash[:16]
    expected = {name: len(artifact["tables"][name]) for name in SOURCE_TABLES}
    output = db.run(import_sql(schema, source_hash, expected, stage.verification_sql(artifact, schema, source_hash, True)))
    result = stage.parse_json([line for line in output.splitlines() if line.startswith('{')][-1])
    result.update(sourceArtifactSha256=source_hash, targetIdentitySha256=target["identitySha256"], productionChanged=False)
    stage.private_write(args.report, stage.encoded(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("container", "user", "database", "artifact-sha256", "target-sha256"):
        parser.add_argument("--" + name, required=True)
    for name in ("artifact", "report"):
        parser.add_argument("--" + name, type=Path, required=True)
    try:
        print(json.dumps(run(parser.parse_args()), sort_keys=True))
    except (stage.StageError, ValueError, KeyError, TypeError, OSError):
        print("Task materialization refused; no source values disclosed. Inspect pinned inputs and candidate state.", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__": main()
