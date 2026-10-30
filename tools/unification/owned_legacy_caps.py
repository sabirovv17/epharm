#!/usr/bin/env python3
"""Verify historical QR aliases on an isolated candidate; never issue tokens or replay work."""
import argparse
import json
from pathlib import Path
import sys

import crm_stage as stage
import owned_automation
import owned_merch as owned


def projection_sql(schema, source_hash, verification):
    stage.require(schema == 'crm_stage_' + stage.hash_arg(source_hash)[:16], 'untrusted staging namespace')
    q = stage.qi(schema)
    key = 'owned_merch_legacy_caps:' + source_hash[:24]
    digest = "encode(sha256(convert_to(to_jsonb(s)::text,'UTF8')),'hex')"
    expected = """SELECT s.id AS link_id,s.dispatch_id,d.branch_id,s.token_hash,
        s.issued_channel AS source_channel,s.expires_at AS source_expires_at,%s AS source_payload_sha256,
        %s AS source_artifact_sha256 FROM %s.staff_task_dispatch_links s
        JOIN %s.staff_task_dispatches d ON d.id=s.dispatch_id""" % (digest, stage.literal(source_hash), q, q)
    fields = ('dispatch_id', 'branch_id', 'token_hash', 'source_channel', 'source_expires_at',
              'source_payload_sha256', 'source_artifact_sha256')
    differs = ' OR '.join('a.' + f + ' IS DISTINCT FROM e.' + f for f in fields)
    return '\n'.join([
        'BEGIN ISOLATION LEVEL REPEATABLE READ;', stage.SETTINGS,
        'SELECT pg_advisory_xact_lock(817106,6);', verification,
        owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_delivery:" + source_hash[:24] + "' AND source_sha256=" + stage.literal(source_hash) + ')', 'verified delivery projection required'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + '.staff_task_kiosk_links) AND NOT EXISTS(SELECT 1 FROM ' + q + '.staff_task_kiosk_devices)', 'kiosk capability projection requires an explicit device lineage'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + ".staff_task_dispatch_links WHERE token_hash !~ '^[0-9a-f]{64}$' OR (issued_channel IS NOT NULL AND issued_channel<>'pharmapay_posm'))", 'unknown capability hash or issuer channel'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM ' + q + '.staff_task_dispatch_links s LEFT JOIN public.merch_task_links l ON l.id=s.id LEFT JOIN public.merch_task_dispatches d ON d.id=s.dispatch_id WHERE l.id IS NULL OR d.id IS NULL OR l.dispatch_id IS DISTINCT FROM s.dispatch_id OR l.source_payload IS DISTINCT FROM to_jsonb(s) OR l.token_hash IS DISTINCT FROM s.token_hash OR l.expires_at IS DISTINCT FROM s.expires_at OR l.issued_channel IS DISTINCT FROM s.issued_channel OR d.source_system<>\'MERCH_CRM\' OR d.source_origin<>\'https://crm.inkar.kz\' OR d.source_payload IS DISTINCT FROM (SELECT to_jsonb(x) FROM ' + q + '.staff_task_dispatches x WHERE x.id=s.dispatch_id))', 'exact source capability lineage differs'),
        'CREATE TEMP TABLE _legacy_caps_expected ON COMMIT DROP AS ' + expected + ';',
        'CREATE TEMP TABLE _legacy_caps_replay ON COMMIT DROP AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=' + stage.literal(key) + ') AS done;',
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_legacy_capability_aliases) OR (SELECT done FROM _legacy_caps_replay)', 'existing aliases require matching replay provenance'),
        'INSERT INTO public.merch_legacy_capability_aliases(link_id,' + ','.join(fields) + ') SELECT link_id,' + ','.join(fields) + ' FROM _legacy_caps_expected WHERE NOT(SELECT done FROM _legacy_caps_replay);',
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.merch_legacy_capability_aliases a FULL JOIN _legacy_caps_expected e USING(link_id) WHERE a.link_id IS NULL OR e.link_id IS NULL OR ' + differs + ')', 'immutable capability alias projection differs'),
        owned.assertion('NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key=' + stage.literal(key) + ' AND (source_sha256 IS DISTINCT FROM ' + stage.literal(source_hash) + ' OR identity_sha256 IS DISTINCT FROM ' + stage.literal(source_hash) + '))', 'capability alias provenance differs'),
        'INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT ' + ','.join(map(stage.literal, [key, source_hash, source_hash])) + ",'{}'::jsonb WHERE NOT(SELECT done FROM _legacy_caps_replay);",
        "SELECT json_build_object('mode',CASE WHEN (SELECT done FROM _legacy_caps_replay) THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,'verifiedAliases',(SELECT count(*) FROM public.merch_legacy_capability_aliases),'issuedLinks',0,'replayedAcknowledgements',0,'newRewards',0);",
        'COMMIT;',
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('container', 'user', 'database', 'artifact-sha256', 'target-sha256'):
        parser.add_argument('--' + name, required=True)
    for name in ('artifact', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    try:
        print(json.dumps(owned_automation.run(parser.parse_args(), project=projection_sql), sort_keys=True))
    except (stage.StageError, ValueError, KeyError, TypeError, OSError, IndexError):
        print('Legacy capability projection refused; private source values suppressed.', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
