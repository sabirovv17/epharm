#!/usr/bin/env python3
"""Explicit native delivery projection on an isolated candidate, without issuing a QR or replaying ACKs."""
import argparse
import json
from pathlib import Path
import sys

import crm_stage as stage
import owned_merch as owned


def projection_sql(schema, source_hash, verification):
    stage.require(schema == 'crm_stage_' + stage.hash_arg(source_hash)[:16], 'untrusted staging namespace')
    q = stage.qi(schema)
    key = 'owned_merch_delivery:' + source_hash[:24]
    done = '(SELECT done FROM _delivery_replay)'
    dispatch_equal = """d.source_media_plan_id IS NOT DISTINCT FROM s.media_plan_id AND
        d.media_plan_id IS NOT DISTINCT FROM p.id AND d.automation_kind IS NOT DISTINCT FROM s.automation_kind AND
        d.sent_at IS NOT DISTINCT FROM s.sent_at AND d.assignment_ready_at IS NOT DISTINCT FROM s.assignment_ready_at AND
        d.sla_started_at IS NOT DISTINCT FROM s.sla_started_at"""
    native = "(s.issued_channel='pharmapay_posm' AND b.pharmacy_binding_verified AND b.epharm_pharmacy_id IS NOT NULL)"
    return '\n'.join([
        'BEGIN ISOLATION LEVEL REPEATABLE READ;', stage.SETTINGS, 'SELECT pg_advisory_xact_lock(817106,3);', verification,
        owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_tasks:" + source_hash[:24] + "')", 'verified task import required'),
        'CREATE TEMP TABLE _delivery_replay AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=' + stage.literal(key) + ') AS done;',
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_task_dispatches d JOIN ' + q + '.staff_task_dispatches s ON s.id=d.id WHERE d.source_payload IS DISTINCT FROM to_jsonb(s))', 'source dispatch proof differs'),
        'UPDATE public.merch_task_dispatches d SET source_media_plan_id=s.media_plan_id,media_plan_id=p.id,automation_kind=s.automation_kind,sent_at=s.sent_at,assignment_ready_at=s.assignment_ready_at,sla_started_at=s.sla_started_at FROM ' + q + '.staff_task_dispatches s LEFT JOIN public.merch_media_plans p ON p.id=s.media_plan_id WHERE d.id=s.id AND NOT ' + done + ';',
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_task_dispatches d JOIN ' + q + '.staff_task_dispatches s ON s.id=d.id LEFT JOIN public.merch_media_plans p ON p.id=s.media_plan_id WHERE NOT(' + dispatch_equal + '))', 'dispatch delivery projection differs'),
        'UPDATE public.merch_task_items i SET training_material_id=s.training_material_id FROM ' + q + '.staff_tasks s WHERE i.id=s.id AND NOT ' + done + ';',
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_task_items i JOIN ' + q + '.staff_tasks s ON s.id=i.id WHERE i.training_material_id IS DISTINCT FROM s.training_material_id)', 'training material projection differs'),
        'UPDATE public.merch_task_links l SET native_owned=coalesce(' + native + ',false),created_at=s.created_at FROM ' + q + '.staff_task_dispatch_links s JOIN public.merch_task_dispatches d ON d.id=s.dispatch_id JOIN public.merch_branches b ON b.id=d.branch_id WHERE l.id=s.id AND NOT ' + done + ';',
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_task_links l JOIN ' + q + '.staff_task_dispatch_links s ON s.id=l.id JOIN public.merch_task_dispatches d ON d.id=s.dispatch_id JOIN public.merch_branches b ON b.id=d.branch_id WHERE l.native_owned IS DISTINCT FROM coalesce(' + native + ',false) OR l.created_at IS DISTINCT FROM s.created_at OR l.token_hash IS DISTINCT FROM s.token_hash OR l.source_payload IS DISTINCT FROM to_jsonb(s))', 'link ownership or original capability differs'),
        'INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT ' + ','.join(map(stage.literal, [key,source_hash,source_hash])) + ",'{}'::jsonb WHERE NOT " + done + ';',
        "SELECT json_build_object('mode',CASE WHEN " + done + " THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,'nativeOwnedLinks',(SELECT count(*) FROM public.merch_task_links WHERE native_owned),'legacyCompatibleLinks',(SELECT count(*) FROM public.merch_task_links WHERE NOT native_owned),'verifiedParentPlans',(SELECT count(*) FROM public.merch_task_dispatches WHERE media_plan_id IS NOT NULL),'missingParentPlans',(SELECT count(*) FROM public.merch_task_dispatches WHERE source_media_plan_id IS NOT NULL AND media_plan_id IS NULL));", 'COMMIT;',
    ])


def run(args):
    stage.require(not args.report.exists() and not args.report.is_symlink(), 'new report required')
    stage.require(args.artifact.stat().st_mode & 0o077 == 0, 'private artifact required')
    stage.require(args.report.resolve() != args.artifact.resolve(), 'report cannot replace input')
    artifact = stage.parse_json(args.artifact.read_bytes()); source_hash = stage.file_hash(args.artifact)
    stage.require(source_hash == stage.hash_arg(args.artifact_sha256), 'source pin mismatch'); stage.validate_artifact(artifact,57)
    stage.require(args.database == 'epharm_unified_candidate', 'designated candidate only')
    db = stage.Database(args.container,args.user,args.database); target = db.inspect()
    stage.require(target['identitySha256'] == stage.hash_arg(args.target_sha256), 'target pin mismatch')
    stage.require(target['versionNum']//10000 == 16 and target['freeBytes'] >= stage.MIN_HEADROOM, 'candidate capacity/version mismatch')
    schema = 'crm_stage_' + source_hash[:16]
    out = db.run(projection_sql(schema,source_hash,stage.verification_sql(artifact,schema,source_hash,True)))
    result = stage.parse_json([line for line in out.splitlines() if line.startswith('{')][-1])
    result.update(sourceArtifactSha256=source_hash,targetIdentitySha256=target['identitySha256'],productionChanged=False,issuedLinks=0,replayedAcknowledgements=0)
    stage.private_write(args.report,stage.encoded(result));return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('container','user','database','artifact-sha256','target-sha256'):parser.add_argument('--'+name,required=True)
    for name in ('artifact','report'):parser.add_argument('--'+name,type=Path,required=True)
    try: print(json.dumps(run(parser.parse_args()),sort_keys=True))
    except (stage.StageError,ValueError,KeyError,TypeError,OSError):
        print('Delivery projection refused; private source values suppressed.',file=sys.stderr);raise SystemExit(1)


if __name__ == '__main__':main()
