#!/usr/bin/env python3
"""Project preserved weekly cycles and explicit source MAS assignments; never run a worker."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import sys

import crm_stage as stage
import owned_merch as owned


def projection_sql(schema, source_hash, verification):
    stage.require(schema == 'crm_stage_' + stage.hash_arg(source_hash)[:16], 'untrusted staging namespace')
    q = stage.qi(schema)
    key = 'owned_merch_automation:' + source_hash[:24]
    done = '(SELECT done FROM _automation_replay)'
    # Current snapshot has no photo rows. A later snapshot needs its verified media route mapping, not a guessed public URL.
    sql = ['BEGIN ISOLATION LEVEL REPEATABLE READ;', stage.SETTINGS,
           'SELECT pg_advisory_xact_lock(817106,4);', verification,
           owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_delivery:" + source_hash[:24] + "')", 'verified delivery projection required'),
           owned.assertion("NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key IN ('owned_merch_delivery:" + source_hash[:24] + "'," + stage.literal(key) + ') AND (source_sha256 IS DISTINCT FROM ' + stage.literal(source_hash) + ' OR identity_sha256 IS DISTINCT FROM ' + stage.literal(source_hash) + '))', 'projection provenance differs'),
           owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + '.placement_item_client_photos) AND NOT EXISTS(SELECT 1 FROM ' + q + '.advertising_place_photos)', 'photo route projection requires explicit verification'),
           'CREATE TEMP TABLE _automation_replay AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=' + stage.literal(key) + ') AS done;']
    projections = [
        ('merch_media_plans', 'media_plans', {'reviewed_at': 's.reviewed_at'}),
        ('merch_task_dispatches', 'staff_task_dispatches', {
            'recurring_cycle_number': 's.recurring_cycle_number', 'cycle_starts_at': 's.cycle_starts_at',
            'next_cycle_at': 's.next_cycle_at'}),
        ('merch_task_items', 'staff_tasks', {
            'media_plan_item_id': 's.media_plan_item_id', 'shelf_id': 's.shelf_id',
            'recurring_cycle_number': 's.recurring_cycle_number', 'automation_kind': 's.automation_kind',
            'reference_image_ref': 's.reference_image_url'}),
    ]
    for target, source, fields in projections:
        join = ' FROM ' + q + '.' + source + ' s JOIN public.' + target + ' d ON d.id=s.id'
        namespace = "d.source_id IS NOT DISTINCT FROM s.id" + (" AND d.dispatch_id IS NOT DISTINCT FROM s.dispatch_id" if target == 'merch_task_items' else " AND d.source_system IS NOT DISTINCT FROM 'MERCH_CRM' AND d.source_origin IS NOT DISTINCT FROM 'https://crm.inkar.kz'")
        sql += [owned.assertion('(SELECT count(*)' + join + ')=(SELECT count(*) FROM ' + q + '.' + source + ')', 'source projection endpoint missing'),
                owned.assertion('NOT EXISTS(SELECT 1' + join + ' WHERE NOT(' + namespace + ') OR d.source_payload IS NULL OR NOT(d.source_payload @> to_jsonb(s)))', 'source ownership proof differs')]
        clean = ' AND '.join('(d.' + name + ' IS NULL OR d.' + name + ' IS NOT DISTINCT FROM (' + value + '))' for name, value in fields.items())
        sql += [owned.assertion('NOT EXISTS(SELECT 1' + join + ' WHERE NOT(' + clean + '))', 'existing automation projection conflicts'),
                'UPDATE public.' + target + ' d SET ' + ','.join(name + '=' + value for name, value in fields.items()) + ' FROM ' + q + '.' + source + ' s WHERE d.id=s.id AND NOT ' + done + ';',
                owned.assertion('NOT EXISTS(SELECT 1' + join + ' WHERE NOT(' + ' AND '.join('d.' + name + ' IS NOT DISTINCT FROM (' + value + ')' for name, value in fields.items()) + '))', 'automation projection differs')]
    sql += [owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_task_dispatches WHERE source_system=\'MERCH_CRM\' AND native_automation_generated)', 'source import cannot become a native generated cycle'),
            owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + ".member_branch_access m JOIN " + q + ".app_users u ON u.id=m.user_id AND u.active AND u.role::text='mass_manager' LEFT JOIN public.unified_external_accounts a ON a.id=u.id WHERE a.id IS NULL OR a.source_system IS DISTINCT FROM 'MERCH_CRM' OR a.source_origin IS DISTINCT FROM 'https://crm.inkar.kz' OR a.source_payload IS DISTINCT FROM to_jsonb(u))", 'source owner registry proof differs'),
            'CREATE TEMP TABLE _source_owner_candidates AS SELECT DISTINCT m.branch_id,m.user_id FROM ' + q + '.member_branch_access m JOIN ' + q + ".app_users u ON u.id=m.user_id AND u.active AND u.role::text='mass_manager' JOIN public.unified_external_accounts a ON a.id=u.id AND a.active AND a.source_role='mass_manager';",
            'CREATE TEMP TABLE _source_owners AS SELECT branch_id,min(user_id::text)::uuid AS source_owner_id FROM _source_owner_candidates GROUP BY branch_id HAVING count(*)=1;',
            owned.assertion(done + ' OR NOT EXISTS(SELECT 1 FROM public.merch_automation_branch_owners)', 'owner registry must be empty on first projection'),
            'INSERT INTO public.merch_automation_branch_owners(branch_id,source_system,source_owner_id,active,revision,verification_reference,created_at,updated_at) SELECT branch_id,\'MERCH_CRM\',source_owner_id,true,1,' + stage.literal('verified-source-membership:' + source_hash) + ',now(),now() FROM _source_owners WHERE NOT ' + done + ';',
            owned.assertion('NOT EXISTS(SELECT 1 FROM _source_owners s LEFT JOIN public.merch_automation_branch_owners o ON o.branch_id=s.branch_id WHERE o.source_owner_id IS DISTINCT FROM s.source_owner_id OR o.source_system IS DISTINCT FROM \'MERCH_CRM\' OR NOT o.active OR o.revision<>1 OR o.admin_account_id IS NOT NULL OR o.person_id IS NOT NULL OR o.verification_reference IS DISTINCT FROM ' + stage.literal('verified-source-membership:' + source_hash) + ')', 'source owner assignment differs'),
            owned.assertion('(SELECT count(*) FROM public.merch_automation_branch_owners)=(SELECT count(*) FROM _source_owners)', 'owner set differs'),
            'INSERT INTO public.merch_automation_owner_audit(id,branch_id,revision,operation,details,created_at) SELECT gen_random_uuid(),branch_id,1,\'source_projection\',jsonb_build_object(\'sourceSha256\',' + stage.literal(source_hash) + "),now() FROM _source_owners WHERE NOT " + done + ';',
            owned.assertion('NOT EXISTS(SELECT 1 FROM _source_owners s LEFT JOIN public.merch_automation_owner_audit a ON a.branch_id=s.branch_id AND a.revision=1 WHERE a.id IS NULL OR a.operation IS DISTINCT FROM \'source_projection\' OR a.actor_admin_id IS NOT NULL OR a.details IS DISTINCT FROM jsonb_build_object(\'sourceSha256\',' + stage.literal(source_hash) + ')) AND (SELECT count(*) FROM public.merch_automation_owner_audit WHERE operation=\'source_projection\')=(SELECT count(*) FROM _source_owners)', 'owner audit proof differs'),
            'INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT ' + ','.join(map(stage.literal,[key,source_hash,source_hash])) + ",'{}'::jsonb WHERE NOT " + done + ';',
            "SELECT json_build_object('mode',CASE WHEN " + done + " THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,'projectedPlans',(SELECT count(*) FROM " + q + ".media_plans),'projectedDispatches',(SELECT count(*) FROM " + q + ".staff_task_dispatches),'projectedItems',(SELECT count(*) FROM " + q + ".staff_tasks),'verifiedSourceOwnerBranches',(SELECT count(*) FROM _source_owners),'ambiguousOwnerBranches',(SELECT count(*) FROM (SELECT branch_id FROM _source_owner_candidates GROUP BY branch_id HAVING count(*)>1) ambiguous),'issuedLinks',0,'generatedTasks',0,'newRewards',0);",
            'COMMIT;']
    return '\n'.join(sql)


def run(args, project=projection_sql):
    stage.require(re.fullmatch(r'epharm-(?:unify-candidate|stage-test-automation)-[a-zA-Z0-9_.-]+',args.container), 'dedicated candidate or disposable automation container required')
    with os.fdopen(os.open(args.artifact,os.O_RDONLY | os.O_NOFOLLOW),'rb') as stream:
        info=os.fstat(stream.fileno())
        stage.require(stat.S_ISREG(info.st_mode) and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode) in (0o400,0o600) and info.st_size<=256*1024*1024,'private bounded regular artifact required')
        raw=stream.read(256*1024*1024+1)
    stage.require(len(raw)<=256*1024*1024,'source artifact too large')
    artifact = stage.parse_json(raw); source_hash = stage.digest(raw)
    stage.require(source_hash == stage.hash_arg(args.artifact_sha256), 'source pin mismatch')
    stage.validate_artifact(artifact,57)
    stage.require(args.database == 'epharm_unified_candidate', 'designated candidate only')
    parent=args.report.parent.resolve(strict=True); info=parent.stat()
    stage.require(info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o700,'private 0700 report parent required')
    with os.fdopen(os.open(args.report,os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,0o600),'wb') as report:
        try:
            db = stage.Database(args.container,args.user,args.database); target = db.inspect()
            stage.require(target['identitySha256'] == stage.hash_arg(args.target_sha256), 'target pin mismatch')
            stage.require(target['versionNum']//10000 == 16 and target['freeBytes'] >= stage.MIN_HEADROOM, 'candidate capacity/version mismatch')
            schema = 'crm_stage_' + source_hash[:16]
            target_guard=owned.assertion("current_database()='epharm_unified_candidate' AND (SELECT system_identifier::text FROM pg_control_system())=" + stage.literal(target['systemIdentifier']) + " AND (SELECT oid FROM pg_database WHERE datname=current_database())=" + str(int(target['oid'])) + " AND current_setting('server_version_num')::integer=" + str(int(target['versionNum'])), 'target identity changed')
            output = db.run(project(schema,source_hash,target_guard+'\n'+stage.verification_sql(artifact,schema,source_hash,True)))
            result = stage.parse_json([line for line in output.splitlines() if line.startswith('{')][-1])
            result.update(sourceArtifactSha256=source_hash,targetIdentitySha256=target['identitySha256'],productionChanged=False)
            report.write(stage.encoded(result)); report.flush(); os.fsync(report.fileno()); return result
        except Exception:
            report.write(stage.encoded({'mode':'refused_or_outcome_unknown_verify_before_retry','sourceArtifactSha256':source_hash,'productionChanged':False})); report.flush()
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('container','user','database','artifact-sha256','target-sha256'): parser.add_argument('--'+name,required=True)
    for name in ('artifact','report'): parser.add_argument('--'+name,type=Path,required=True)
    try: print(json.dumps(run(parser.parse_args()),sort_keys=True))
    except (stage.StageError,ValueError,KeyError,TypeError,OSError,IndexError):
        print('Automation projection refused; private source values suppressed.',file=sys.stderr); raise SystemExit(1)


if __name__ == '__main__': main()
