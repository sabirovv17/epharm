#!/usr/bin/env python3
"""Validated, locked Standard N snapshots. No live partition is streamed or cleared."""
import argparse
import json
import os
import re
import subprocess
import sys
import shutil
import hashlib
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
import pim_runtime as runtime
from pim_validation import HEADER, ValidationError, sha256, validate_file, durable_json

TABLES = ('fact_pharmacy_daily', 'pharmacy_daily_summary', 'product_daily_summary', 'daily_summary')
ROOT = runtime.ROOT
LOCK_PATH = ROOT / 'etl.lock'
VALID_WARE_SQL = "match(ware_id,'^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$')"


def now():
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def exclusive_lock(path=LOCK_PATH):
    import fcntl
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError('another PIM snapshot import is running') from exc
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


class Database:
    def __init__(self, runtime_adapter):
        self.runtime = runtime_adapter
        self.database = runtime_adapter.DB
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', self.database):
            raise RuntimeError('invalid database identifier')

    def table(self, name):
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
            raise RuntimeError('invalid table identifier')
        return self.database + '.' + name

    def query(self, sql):
        result = subprocess.run(self.runtime.client_command(sql), capture_output=True, text=True, timeout=600)
        if result.returncode:
            # Do not serialize the full command or query into operational logs.
            raise RuntimeError(f'ClickHouse query failed ({result.returncode}): {result.stderr[:500]}')
        return result.stdout.strip()

    def insert_file(self, sql, path):
        with Path(path).open('rb') as source:
            result = subprocess.run(self.runtime.client_command(sql), stdin=source, capture_output=True, timeout=600)
        if result.returncode:
            raise RuntimeError(f'ClickHouse staged insert failed ({result.returncode}): {result.stderr[:500]!r}')

    def export(self, sql, path):
        with Path(str(path) + '.partial').open('wb') as output:
            result = subprocess.run(self.runtime.client_command(sql), stdout=output, stderr=subprocess.PIPE, timeout=600)
        if result.returncode:
            raise RuntimeError(f'ClickHouse export failed ({result.returncode})')
        Path(str(path) + '.partial').replace(path)

    def count(self, table, date):
        return int(self.query(f"SELECT count() FROM {self.table(table)} WHERE snapshot_date=toDate('{date}')"))


def add_source_columns(db):
    for field in ('retail_price', 'cost_price', 'revenue'):
        # Older snapshots have unknown source missingness: NULL is intentional.
        db.query(f'ALTER TABLE {db.table(TABLES[0])} ADD COLUMN IF NOT EXISTS source_{field} Nullable(Float64)')


def build_stage(db, manifest, artifact, run_id):
    date = manifest['snapshot_date']
    tables = {name: f'{name}__stage_{run_id}' for name in TABLES}
    for live, stage in tables.items():
        db.query(f'CREATE TABLE {db.table(stage)} AS {db.table(live)}')
    schema = ', '.join(f'`{name}` String' for name in HEADER)
    legacy_fields = [
        f"toDate('{date}')", f"'{manifest['source_file']}'", 'toUInt32(rowNumberInAllBlocks()+1)',
        'toUInt64(PART_ID)', 'WARE_ID', 'SNAME', 'toUInt32(`G$PROFILE_ID`)', 'CAPTION',
        'toFloat64(`RETURN`)', 'toFloat64(GOODS_RECEIVED)', 'toFloat64(GOOD_TRANSFERRED)',
        'toFloat64(TRANSFERRED_OUT)', 'toFloat64(ADJUSTMENTS)', 'toFloat64(SALES)',
        "ifNull(toFloat64OrNull(nullIf(RETAIL_PRICE,'')),0)",
        "ifNull(toFloat64OrNull(nullIf(COST_PRICE,'')),0)",
        'toFloat64(STOCK_AT_THE_BEGINNING)', 'toFloat64(STOCK_AT_THE_END)', 'SUPPLIER', 'SERIES',
        "parseDateTimeBestEffortOrNull(nullIf(EXPIRATION_DATE,''))", 'toFloat64(QUANT_OPT)',
        "ifNull(toFloat64OrNull(nullIf(REVENUE,'')),0)", 'now64(3)',
        "toFloat64OrNull(nullIf(RETAIL_PRICE,''))", "toFloat64OrNull(nullIf(COST_PRICE,''))",
        "toFloat64OrNull(nullIf(REVENUE,''))"]
    sql = f"INSERT INTO {db.table(tables[TABLES[0]])} SELECT {', '.join(legacy_fields)} FROM input('{schema}') FORMAT TabSeparatedWithNames"
    db.insert_file(sql, artifact / 'normalized.tsv')
    fact = db.table(tables[TABLES[0]])
    summary_queries = {
        'daily_summary': f"SELECT snapshot_date,count(),uniqExact(profile_id),uniqExactIf(ware_id,ware_id!=''),sum(sales),sum(revenue),sum(goods_received),sum(return_qty),sum(stock_end),countIf(sales<0),countIf(revenue<0),countIf(ware_id=''),now64(3) FROM {fact} GROUP BY snapshot_date",
        'pharmacy_daily_summary': f"SELECT snapshot_date,profile_id,argMax(caption,length(caption)),count(),uniqExactIf(ware_id,ware_id!=''),sum(sales),sum(revenue),sum(goods_received),sum(return_qty),sum(stock_end),now64(3) FROM {fact} GROUP BY snapshot_date,profile_id",
        'product_daily_summary': f"SELECT snapshot_date,ware_id,argMax(sname,length(sname)),argMax(supplier,length(supplier)),count(),uniqExact(profile_id),sum(sales),sum(revenue),sum(goods_received),sum(stock_end),now64(3) FROM {fact} WHERE ware_id!='' GROUP BY snapshot_date,ware_id"}
    for target, query in summary_queries.items():
        db.query(f'INSERT INTO {db.table(tables[target])} {query}')
    result = json.loads(db.query(f"SELECT count() rows,uniqExactIf(ware_id,{VALID_WARE_SQL}) valid_unique_ware_ids,uniqExact(profile_id) pharmacies,countIf(ware_id='') blank_ware_rows,countIf(ware_id!='' AND NOT {VALID_WARE_SQL}) invalid_ware_rows,countIf(source_retail_price IS NULL) missing_retail_price,countIf(source_cost_price IS NULL) missing_cost_price,countIf(source_revenue IS NULL) missing_revenue,countIf(expiration_date IS NULL) missing_expiration_date,countIf(source_retail_price IS NOT NULL AND source_retail_price!=round(source_retail_price,2)) noncent_price_rows,countIf(profile_id=0) zero_pharmacy_rows,countIf(trimBoth(caption)='') blank_caption_rows FROM {fact} FORMAT JSONEachRow"))
    for field in ('rows', 'valid_unique_ware_ids', 'pharmacies'):
        if int(result[field]) != int(manifest[field]):
            raise ValidationError(f'staged ClickHouse {field} differs from validated source')
    for field in ('blank_ware_rows', 'invalid_ware_rows', 'missing_retail_price', 'missing_cost_price', 'missing_revenue', 'missing_expiration_date'):
        if int(result[field]) != int(manifest['quality'].get(field, 0)):
            raise ValidationError(f'staged source missingness differs for {field}')
    if result['zero_pharmacy_rows'] or result['blank_caption_rows']:
        raise ValidationError('staged pharmacy identifiers or captions are incomplete')
    if db.count(tables['daily_summary'], date) != 1:
        raise ValidationError('staged daily summary incomplete')
    if db.count(tables['pharmacy_daily_summary'], date) != int(manifest['pharmacies']):
        raise ValidationError('staged pharmacy summaries incomplete')
    stage_manifest = {'tables': tables, 'run_id': run_id, 'checks': result, 'validated_at': now()}
    durable_json(artifact / 'stage.json', stage_manifest)
    return tables


def publication(db, tables, date, run_id, artifact, before_replace=None):
    backups = {}
    for live in TABLES:
        count = db.count(live, date)
        backup = f'{live}__backup_{run_id}'
        db.query(f'CREATE TABLE {db.table(backup)} AS {db.table(live)}')
        if count:
            db.query(f"ALTER TABLE {db.table(backup)} ATTACH PARTITION '{date}' FROM {db.table(live)}")
            if db.count(backup, date) != count:
                raise ValidationError('partition backup count mismatch')
        backups[live] = {'table': backup, 'rows': count}
    journal = {'snapshot_date': date, 'state': 'backed_up', 'backups': backups, 'replaced': []}
    journal_path = artifact / 'publication.json'
    def save(): durable_json(journal_path, journal)
    save()
    if before_replace:
        before_replace(backups)
    try:
        for live in TABLES:
            journal['state'] = 'publishing'; journal['next_table'] = live; save()
            # Record intent before the network call: a timeout can mean the server committed.
            journal['replaced'].append(live); save()
            db.query(f"ALTER TABLE {db.table(live)} REPLACE PARTITION '{date}' FROM {db.table(tables[live])}")
            if db.count(live, date) != db.count(tables[live], date):
                raise ValidationError('published partition count mismatch')
        journal['state'] = 'complete'; journal['published_at'] = now(); save()
    except BaseException:
        # Replace is atomic within each table; restore all earlier replacements on failure.
        for live in reversed(journal['replaced']):
            backup = backups[live]
            if backup['rows']:
                db.query(f"ALTER TABLE {db.table(live)} REPLACE PARTITION '{date}' FROM {db.table(backup['table'])}")
            elif db.count(live, date):
                db.query(f"ALTER TABLE {db.table(live)} DROP PARTITION '{date}'")
        journal['state'] = 'rolled_back'; save()
        raise
    return journal


def recover_interrupted_publication(db, artifact):
    path = artifact / 'publication.json'
    if not path.exists():
        return
    journal = json.loads(path.read_text())
    if journal['state'] not in ('publishing', 'backed_up'):
        return
    date = journal['snapshot_date']
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', date):
        raise ValidationError('invalid recovery date')
    for live in reversed(journal['replaced']):
        if live not in TABLES:
            raise ValidationError('invalid recovery table')
        backup = journal['backups'][live]
        if backup['rows']:
            if db.count(backup['table'], date) != backup['rows']:
                raise ValidationError('recovery backup is incomplete')
            db.query(f"ALTER TABLE {db.table(live)} REPLACE PARTITION '{date}' FROM {db.table(backup['table'])}")
        elif db.count(live, date):
            db.query(f"ALTER TABLE {db.table(live)} DROP PARTITION '{date}'")
    journal['state'] = 'recovered_after_interruption'; journal['recovered_at'] = now()
    durable_json(path, journal)


def recover_all_interrupted(db, artifact_root):
    if not artifact_root.exists():
        return
    for journal in sorted(artifact_root.glob('*/publication.json')):
        recover_interrupted_publication(db, journal.parent)


def cleanup_terminal_tables(db, artifact, current=None, retention_hours=None):
    """Drop disposable run tables after recovery is no longer needed.

    Stage tables are removed as soon as publication reaches a terminal state.
    Backups remain available for a bounded rollback window. Every successful
    drop is journalled; retrying after a timeout or interruption is safe because
    DROP TABLE IF EXISTS is idempotent.
    """
    artifact=Path(artifact)
    journal_path=artifact/'publication.json'
    if not journal_path.is_file() or journal_path.is_symlink():return
    journal=json.loads(journal_path.read_text())
    if journal.get('state') not in ('complete','rolled_back','recovered_after_interruption'):return
    cleanup=journal.setdefault('cleanup',{'dropped_stages':[],'dropped_backups':[]})
    cleanup.setdefault('dropped_stages',[]);cleanup.setdefault('dropped_backups',[])
    stage_path=artifact/'stage.json'
    stages=[]
    if stage_path.is_file() and not stage_path.is_symlink():
        stage=json.loads(stage_path.read_text())
        for live,name in stage.get('tables',{}).items():
            if live not in TABLES or not name.startswith(live+'__stage_'):
                raise ValidationError('invalid disposable stage table in journal')
            stages.append(name)
    def drop(names, record):
        for name in sorted(set(names)):
            if name in cleanup[record]:continue
            db.query(f'DROP TABLE IF EXISTS {db.table(name)}')
            cleanup[record].append(name);durable_json(journal_path,journal)
    drop(stages,'dropped_stages')
    if retention_hours is None:
        try:retention_hours=int(os.getenv('PIM_BACKUP_RETENTION_HOURS','72'))
        except ValueError as exc:raise RuntimeError('PIM_BACKUP_RETENTION_HOURS is invalid') from exc
    if not 0<=retention_hours<=24*30:
        raise RuntimeError('PIM_BACKUP_RETENTION_HOURS must be between 0 and 720')
    timestamp=journal.get('published_at') or journal.get('recovered_at')
    terminal_at=datetime.fromisoformat(timestamp) if timestamp else datetime.fromtimestamp(journal_path.stat().st_mtime,timezone.utc)
    if terminal_at.tzinfo is None:raise ValidationError('terminal publication timestamp lacks timezone')
    current=current or datetime.now(timezone.utc)
    cleanup['backup_eligible_at']=(terminal_at+timedelta(hours=retention_hours)).isoformat()
    if current>=terminal_at+timedelta(hours=retention_hours):
        backups=[]
        for live,value in journal.get('backups',{}).items():
            name=value['table']
            if live not in TABLES or not name.startswith(live+'__backup_'):
                raise ValidationError('invalid disposable backup table in journal')
            backups.append(name)
        drop(backups,'dropped_backups')
    cleanup['checked_at']=current.isoformat();durable_json(journal_path,journal)


def cleanup_all_terminal(db, artifact_root, current=None, retention_hours=None):
    if not artifact_root.exists():return
    for journal in sorted(artifact_root.glob('*/publication.json')):
        cleanup_terminal_tables(db,journal.parent,current,retention_hours)


def export_catalog(db, table, source_manifest, artifact):
    date = source_manifest['snapshot_date']
    fact = f"(SELECT * FROM {db.table(table)} WHERE snapshot_date=toDate('{date}'))"
    sellability_date = datetime.now(ZoneInfo('Asia/Almaty')).date().isoformat()
    valid = VALID_WARE_SQL
    # Missing/expired dates are not positively asserted as sellable stock.
    cent_precision = 'abs(source_retail_price-round(source_retail_price,2))<=0.000001'
    eligible = f"source_retail_price>0 AND ({cent_precision}) AND expiration_date IS NOT NULL AND toDate(expiration_date)>=toDate('{sellability_date}')"
    money_quality = json.loads(db.query(f"SELECT countIf(source_retail_price IS NOT NULL AND source_retail_price!=round(source_retail_price,2)) noncent_price_batches,countIf(source_retail_price!=round(source_retail_price,2) AND ({cent_precision})) binary_noise_price_batches,countIf(source_retail_price IS NOT NULL AND NOT ({cent_precision})) genuine_subcent_price_batches,countIf(source_retail_price=round(source_retail_price,2) AND source_retail_price!=floor(source_retail_price)) exact_cent_fraction_price_batches FROM {fact} FORMAT JSONEachRow"))
    products = artifact / 'products.ndjson'
    pharmacies = artifact / 'pharmacies.ndjson'
    offers = artifact / 'pharmacy-offers.ndjson'
    db.export(f"SELECT ware_id,argMax(sname,length(sname)) name FROM {fact} WHERE {valid} GROUP BY ware_id ORDER BY ware_id FORMAT JSONEachRow", products)
    db.export(f"SELECT toString(profile_id) pharmacy_id,argMax(caption,length(caption)) name FROM {fact} GROUP BY profile_id ORDER BY profile_id FORMAT JSONEachRow", pharmacies)
    db.export(f"""SELECT ware_id,toString(profile_id) pharmacy_id,
        if(countIf(({eligible}) AND stock_end>0)>0,greatest(0.,sumIf(stock_end,({eligible}) OR stock_end<0)),0.) quantity,
        if(countIf(({eligible}) AND stock_end>0)>0,maxIf(round(source_retail_price,2),({eligible}) AND stock_end>0),NULL) retail_amount,
        sum(stock_end) raw_quantity,count() source_batch_count,
        countIf(source_retail_price IS NULL) missing_price_batches,
        countIf(expiration_date IS NULL) missing_expiry_batches,
        countIf(expiration_date IS NOT NULL AND toDate(expiration_date)<toDate('{sellability_date}')) expired_batches
        FROM {fact} WHERE {valid} GROUP BY ware_id,profile_id ORDER BY ware_id,profile_id FORMAT JSONEachRow""", offers)
    def info(path):
        with path.open('rb') as f: count = sum(1 for _ in f)
        return {'file': path.name, 'sha256': sha256(path), 'count': count, 'bytes': path.stat().st_size}
    products_info, offers_info, pharmacies_info = info(products), info(offers), info(pharmacies)
    snapshot_id = hashlib.sha256((source_manifest['source_sha256']+'|'+sellability_date+'|'+offers_info['sha256']+'|v2').encode()).hexdigest()
    manifest = {'schema_version': 1, 'export_policy_version': 2, 'snapshot_id': snapshot_id,
        'source_date': date, 'sellability_date': sellability_date, 'observed_at': date + 'T23:59:59+05:00',
        'valid_until': (datetime.fromisoformat(date + 'T23:59:59+05:00') + timedelta(hours=48)).isoformat(),
        'observation_time_semantics': 'derived end-of-day Asia/Almaty from source_date, not an exact source timestamp',
        'retrieved_at': source_manifest['observed_at'], 'validated_at': now(),
        'source_sha256': source_manifest['source_sha256'], 'complete': True,
        'source_rows': source_manifest['rows'], 'source_pharmacies': source_manifest['pharmacies'],
        'products': products_info, 'offers': offers_info, 'pharmacies': pharmacies_info,
        'validation': {**source_manifest['quality'], **money_quality,
            'discarded_pim_rows': 0,
            'quarantined_rows': source_manifest['quality'].get('blank_ware_rows',0) + source_manifest['quality'].get('invalid_ware_rows',0)},
        'stock_semantics': 'max(0,sum(known-positive-price nonexpired batch stock plus all negative batch stock)); a positive eligible priced batch is required',
        'price_semantics': 'maximum cent-precision retail price among eligible positive-stock batches; only floating-point noise <=0.000001 KZT normalized to nearest cent with audited count; genuine subcent prices remain in PIM but unsellable without pricing confirmation',
        'not_a_master_catalog': True}
    durable_json(artifact / 'catalog-manifest.json', manifest)
    return manifest


def refresh_published_catalog(db, artifact_root, clock=None):
    current = clock or datetime.now(timezone.utc)
    candidates = []
    for path in artifact_root.glob('*/publication.json'):
        publication_state = json.loads(path.read_text())
        if publication_state.get('state') == 'complete':
            candidates.append((publication_state['snapshot_date'], publication_state.get('published_at',''), path.parent))
    if not candidates:
        raise RuntimeError('no completely published snapshot available for catalogue handoff')
    artifact = max(candidates, key=lambda item:(item[0],item[1]))[2]
    source = json.loads((artifact/'manifest.json').read_text())
    observed = datetime.fromisoformat(source['snapshot_date']+'T23:59:59+05:00')
    if current >= observed + timedelta(hours=48):
        raise RuntimeError('latest complete Standard N source is over48h old; stock handoff is stale')
    today = current.astimezone(ZoneInfo('Asia/Almaty')).date().isoformat()
    catalog_path = artifact/'catalog-manifest.json'
    catalog = json.loads(catalog_path.read_text()) if catalog_path.exists() else {}
    if catalog.get('sellability_date') == today and catalog.get('export_policy_version') == 2:
        return artifact
    if db.count(TABLES[0],source['snapshot_date']) != source['rows']:
        raise ValidationError('published source rowcount changed before expiry projection')
    export_catalog(db, TABLES[0], source, artifact)
    return artifact


def write_etl_log(db, source, status, rows, error=''):
    esc=lambda value: value.replace('\\','\\\\').replace("'","\\'")
    db.query(f"INSERT INTO {db.table('etl_file_log')} VALUES ('{esc(source['source_file'])}',toDate('{source['snapshot_date']}'),'{status}',{rows},{source['source_bytes']},'{source['source_sha256']}','{esc(error[:1000])}',now64(3))")


def process_one(db, runtime_adapter, name, size, args):
    previous = db.query(f"SELECT status,byte_size FROM {db.table('etl_file_log')} FINAL WHERE file_name='{name}' LIMIT 1 FORMAT JSONEachRow")
    previous = json.loads(previous) if previous else {}
    if previous.get('status') == 'done' and int(previous.get('byte_size',0)) == size and not args.force:
        print(json.dumps({'skipped_complete': name})); return
    path = runtime_adapter.download_file(name, size)
    if shutil.disk_usage(ROOT).free < max(size*3, 3*1024**3):
        raise RuntimeError('insufficient free space for a complete staged snapshot; no live data changed')
    source, artifact = validate_file(path, ROOT / 'validated', size)
    if args.validate_only:
        print(json.dumps({'validated': source, 'artifact': str(artifact)}, ensure_ascii=False)); return
    add_source_columns(db)
    recover_interrupted_publication(db, artifact)
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '_' + source['source_sha256'][:8]
    tables = build_stage(db, source, artifact, run_id)
    catalog = export_catalog(db, tables[TABLES[0]], source, artifact)
    if args.stage_only:
        print(json.dumps({'stage_complete': True, 'stage_tables': tables, 'artifact': str(artifact), 'catalog': catalog}, ensure_ascii=False));return
    def before_replace(backups):
        write_etl_log(db, source, 'publishing', 0)
        print(json.dumps({'publishing_complete_snapshot': name, 'backups': backups}), flush=True)
    try:
        published = publication(db, tables, source['snapshot_date'], run_id, artifact, before_replace)
    except BaseException as exc:
        write_etl_log(db, source, 'failed', 0, type(exc).__name__)
        raise
    write_etl_log(db, source, 'done', source['rows'])
    cleanup_terminal_tables(db,artifact)
    print(json.dumps({'published': published, 'catalog_manifest': str(artifact/'catalog-manifest.json')}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--latest', type=int)
    parser.add_argument('--file')
    parser.add_argument('--from-date')
    parser.add_argument('--to-date')
    parser.add_argument('--repair-rejects', action='store_true')
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--keep', action='store_true', help='original sources are always retained')
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--stage-only', action='store_true')
    parser.add_argument('--refresh-published-only', action='store_true', help='refresh expiry projection and deliver; no SMB access or source import')
    args = parser.parse_args()
    if not args.file and not args.from_date and not args.latest and not args.repair_rejects:
        args.latest = 1
    db = Database(runtime)
    with exclusive_lock():
        recover_all_interrupted(db, ROOT / 'validated')
        cleanup_all_terminal(db, ROOT / 'validated')
        source_error = None
        if not args.refresh_published_only:
            try:
                files = runtime.remote_files()
                selected = runtime.select_files(files, args)
                for name in selected:
                    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}\.csv', name):
                        raise ValidationError('invalid source date')
                    process_one(db, runtime, name, files[name], args)
            except Exception as exc:
                if args.validate_only or args.stage_only:
                    raise
                source_error = exc
        if not args.validate_only and not args.stage_only:
            refresh_published_catalog(db, ROOT / 'validated')
        push=Path(__file__).with_name('pim_medusa_push.py')
        receiver_config=Path(os.getenv('PIM_MEDUSA_CONFIG','/etc/pim-dashboard/medusa-push.json'))
        if not args.validate_only and not args.stage_only and push.exists() and receiver_config.exists():
            result=subprocess.run([sys.executable,str(push)],capture_output=True,text=True,timeout=300)
            if result.returncode:
                raise RuntimeError('PIM snapshot is complete but Medusa delivery failed; next timer run will retry: '+result.stderr[-600:])
            print(result.stdout.strip())
        if source_error:
            raise source_error


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(json.dumps({'status':'failed','error_type':type(exc).__name__,'error':str(exc)[:1500]}),file=sys.stderr)
        sys.exit(1)
