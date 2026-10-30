#!/usr/bin/env python3
"""Preserve shared monthly access history on a pinned candidate; never issue credentials."""
import argparse
import json
from pathlib import Path
import sys

import crm_stage as stage
import owned_automation
import owned_merch as owned


def projections(schema):
    q = stage.qi(schema)
    code = {name: 's.' + name for name in (
        'id organization_id placement_month code_hash deadline_at status max_uses use_count '
        'qr_token_hash qr_created_at qr_last_used_at qr_use_count last_used_at revoked_at '
        'created_at updated_at note').split()}
    code.update(source_system="'MERCH_CRM'", source_origin=stage.literal(owned.ORIGIN),
        source_id='s.id', source_payload='to_jsonb(s)', code_lookup_hash='NULL::varchar',
        all_org_branches='NOT EXISTS(SELECT 1 FROM ' + q + '.staff_access_code_branches b WHERE b.code_id=s.id)',
        manual_revoked_at='CASE WHEN s.revoked_by IS NOT NULL THEN s.revoked_at ELSE NULL END',
        created_by_account_id='NULL::uuid', revoked_by_account_id='NULL::uuid')
    branches = {name: 's.' + name for name in 'code_id branch_id created_at'.split()}
    attempts = {name: 's.' + name for name in 'id access_type lookup_hash code_id success failure_reason created_at'.split()}
    attempts.update(ip_hash="encode(sha256(convert_to('staff-access:source-ip:'||coalesce(s.request_ip,''),'UTF8')),'hex')",
        bcrypt_cost='0', source_id='s.id', source_payload='to_jsonb(s)')
    return [
        ('merch_staff_access_codes', 'staff_access_codes', ('id',), code),
        ('merch_staff_access_code_branches', 'staff_access_code_branches', ('code_id', 'branch_id'), branches),
        ('merch_staff_access_attempts', 'staff_access_attempts', ('id',), attempts),
    ]


def projection_sql(schema, source_hash, verification):
    stage.require(schema == 'crm_stage_' + stage.hash_arg(source_hash)[:16], 'untrusted staging namespace')
    q = stage.qi(schema)
    key = 'owned_merch_staff_access:' + source_hash[:24]
    done = '(SELECT done FROM _staff_access_replay)'
    sql = ['BEGIN ISOLATION LEVEL REPEATABLE READ;', stage.SETTINGS,
        'SELECT pg_advisory_xact_lock(817106,9);', verification,
        owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_context:"
            + source_hash[:24] + "' AND source_sha256=" + stage.literal(source_hash) + ')',
            'verified recommendation context projection required'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + ".user_sessions WHERE session_kind='staff_access' "
            'AND revoked_at IS NULL AND expires_at>statement_timestamp())',
            'active legacy staff sessions require explicit continuation before cutover'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + '.visit_reports)',
            'nonempty source visit history requires a separately verified attempt projection'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_staff_access_sessions) '
            'AND NOT EXISTS(SELECT 1 FROM public.merch_staff_visits)',
            'staff runtime sessions and visits must remain pristine during initial import'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='
            + stage.literal(key) + ' AND (source_sha256 IS DISTINCT FROM ' + stage.literal(source_hash)
            + ' OR identity_sha256 IS DISTINCT FROM ' + stage.literal(source_hash) + '))',
            'shared access import provenance differs'),
        'CREATE TEMP TABLE _staff_access_replay ON COMMIT DROP AS SELECT EXISTS(SELECT 1 FROM '
            'public.unified_import_runs WHERE operation_key=' + stage.literal(key) + ') AS done;',
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + '.staff_access_codes s LEFT JOIN '
            'public.merch_organizations o ON o.id=s.organization_id WHERE o.id IS NULL OR '
            "o.source_system<>'MERCH_CRM' OR o.source_payload IS DISTINCT FROM (SELECT to_jsonb(x) FROM "
            + q + '.organizations x WHERE x.id=s.organization_id))', 'exact source organization lineage differs'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + '.staff_access_code_branches s JOIN '
            + q + '.staff_access_codes c ON c.id=s.code_id LEFT JOIN public.merch_branches b ON '
            'b.id=s.branch_id WHERE b.id IS NULL OR b.organization_id IS DISTINCT FROM c.organization_id '
            "OR b.source_system<>'MERCH_CRM' OR b.source_payload IS DISTINCT FROM (SELECT to_jsonb(x) FROM "
            + q + '.branches x WHERE x.id=s.branch_id))', 'exact source branch/scope lineage differs')]
    for index, (target, source, primary, fields) in enumerate(projections(schema)):
        expected = '_staff_access_expected_' + str(index)
        names = ','.join(map(stage.qi, fields))
        select = ','.join(value + ' AS ' + stage.qi(name) for name, value in fields.items())
        join = ' AND '.join('t.' + stage.qi(name) + '=e.' + stage.qi(name) for name in primary)
        differs = ' OR '.join('t.' + stage.qi(name) + ' IS DISTINCT FROM e.' + stage.qi(name) for name in fields)
        sql += ['CREATE TEMP TABLE ' + expected + ' ON COMMIT DROP AS SELECT ' + select
                + ' FROM ' + q + '.' + source + ' s;',
            owned.assertion(done + ' OR NOT EXISTS(SELECT 1 FROM public.' + target + ')',
                'shared access target is not pristine'),
            'INSERT INTO public.' + target + '(' + names + ') SELECT ' + names + ' FROM '
                + expected + ' WHERE NOT ' + done + ';',
            owned.assertion('NOT EXISTS(SELECT 1 FROM public.' + target + ' t FULL JOIN ' + expected
                + ' e ON ' + join + ' WHERE t.' + primary[0] + ' IS NULL OR e.' + primary[0]
                + ' IS NULL OR ' + differs + ')', 'shared access source fields or replay differ')]
    sql += ['INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT '
        + ','.join(map(stage.literal, [key, source_hash, source_hash])) + ",'{}'::jsonb WHERE NOT " + done + ';',
        "SELECT json_build_object('mode',CASE WHEN " + done
        + " THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,"
        "'codes',(SELECT count(*) FROM public.merch_staff_access_codes),"
        "'scopeRows',(SELECT count(*) FROM public.merch_staff_access_code_branches),"
        "'historicalAttempts',(SELECT count(*) FROM public.merch_staff_access_attempts),"
        "'issuedCredentials',0,'issuedSessions',0,'generatedVisits',0,'newRewards',0);", 'COMMIT;']
    return '\n'.join(sql)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('container', 'user', 'database', 'artifact-sha256', 'target-sha256'):
        parser.add_argument('--' + name, required=True)
    for name in ('artifact', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    try:
        print(json.dumps(owned_automation.run(parser.parse_args(), project=projection_sql), sort_keys=True))
    except (stage.StageError, ValueError, KeyError, TypeError, OSError, IndexError):
        print('Shared access projection refused; private source values suppressed.', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
