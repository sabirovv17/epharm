import copy
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import crm_stage as stage
import owned_context as context
from test_crm_stage import column,constraint,fixture

TOOLS=Path(__file__).resolve().parents[1]
ROOT=TOOLS.parents[1]
ORG='10000000-0000-4000-8000-000000000001'
BRANCH='20000000-0000-4000-8000-000000000001'
SIGNAL='30000000-0000-4000-8000-000000000001'

def artifact(empty=False):
    metadata=copy.deepcopy(fixture()['metadata']);metadata.update(tables=[],enums=[],sequences=[],triggers=[],routines=[])
    tables={}
    for target,(name,fields) in context.TABLES.items():
        cols=[]
        for index,f in enumerate(fields,1):
            kind='text'
            if f in ('organization_id','branch_id') or f=='id' and name.endswith('signals'):kind='uuid'
            elif f=='id':kind='int8'
            elif f=='active':kind='bool'
            elif f in ('bonus','priority'):kind='int4'
            elif f=='advantages':kind='jsonb'
            elif f.endswith('_at') or f in ('valid_from','valid_until'):kind='timestamptz'
            elif f.endswith('_qty'):kind='numeric'
            cols.append(column(f,kind,position=index,notNull=f=='id'))
        metadata['tables'].append(dict(name=name,kind='r',columns=cols,constraints=[constraint(name+'_pkey','p',['id'])]))
        row=dict(organization_id=ORG,branch_id=BRANCH,created_at='2026-10-30 00:00:00+00',updated_at='2026-10-30 00:00:00+00')
        if name.endswith('signals'):
            row.update(id=SIGNAL,kind='substitution',trigger_barcode='4871234567890',recommend_product_id='exact-offer',script='SYNTHETIC-private-script',advantages='["first"]',bonus='123',priority='9',active='t')
        else:
            row.update(id='123',external_key='opaque-stock-key',product_id='exact-offer',pharmacy_available_qty='3.140',quality='confirmed',source='reviewed-synthetic-stock',observed_at='2026-10-30 00:00:00+00')
        tables[name]=[] if empty else [[row.get(f) for f in fields]]
    for n in range(55):
        name='preserved_'+str(n).zfill(2);metadata['tables'].append(dict(name=name,kind='r',columns=[column('id',notNull=True)],constraints=[constraint(name+'_pkey','p',['id'])]));tables[name]=[]
    metadata['tables'].sort(key=lambda t:t['name'])
    return dict(formatVersion=1,capturedAt='2026-10-30T00:00:00Z',sourceIdentitySha256='a'*64,sourceBackupSha256='b'*64,
                sourceSchemaSha256=stage.schema_hash(metadata),metadata=metadata,sequenceStates=[],tables=tables,
                checksums=[stage.table_checksum(t,tables[t['name']]) for t in metadata['tables']])


def manual(qty=4,when='2026-10-30T01:00:00Z'):
    return json.dumps({'pharmacies':[{'pharmacyId':'exact-pharmacy','recommendations':[],
        'balances':[{'productId':'exact-offer','externalKey':'opaque-stock-key','pharmacyAvailableQty':qty,
                     'quality':'confirmed','source':'reviewed-input','observedAt':when}]}]}).encode()


class ContextUnitTest(unittest.TestCase):
    def test_exact_source_fields_no_stock_inference_or_business_replay(self):
        sql=context.projection_sql('crm_stage_'+'a'*16,'a'*64,'SELECT 123;',False)
        self.assertIn('s.id',sql);self.assertIn('to_jsonb(s)',sql);self.assertIn('verified extended source projection required',sql)
        self.assertIn('ROLLBACK;\nSELECT pg_advisory_unlock',sql)
        for forbidden in ('staff_task_stock','INSERT INTO public.pharmacist_wallet','INSERT INTO public.merch_task','DELETE ','TRUNCATE '):self.assertNotIn(forbidden,sql)
        with self.assertRaises(stage.StageError):context.projection_sql('public','a'*64,'')

    def test_manual_decimal_null_ids_and_validation_are_lossless(self):
        data=b'{"pharmacies":[{"pharmacyId":"p","balances":[{"productId":"x","source":"reviewed","observedAt":"2026-10-30T00:00:00Z","pharmacyAvailableQty":1.230,"networkAvailableQty":null}]}]}'
        _,balances,count=context.load_manual(data);self.assertEqual(1,count);self.assertEqual('1.230',balances[0]['pharmacyAvailableQty']);self.assertIsNone(balances[0]['networkAvailableQty'])
        for raw in [manual(qty=-1),manual(qty=1.0001),manual(qty=True),manual(when='2026-10-30'),b'{"pharmacies":[],"pharmacies":[]}']:
            with self.subTest(raw=raw),self.assertRaises((stage.StageError,ValueError)):context.load_manual(raw)
        with self.assertRaises(stage.StageError):context.manual_sql('crm_stage_'+'a'*16,'a'*64,'',manual(),None,True,1)


@unittest.skipUnless(os.environ.get('RUN_OWNED_CONTEXT_PG_TESTS')=='1','opt-in isolated PG16 context projection/sync')
class ContextPostgresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name).resolve();cls.root.chmod(0o700)
        cls.name='epharm-stage-test-automation-context-'+str(time.time_ns());cls.container_id=None
        cls.addClassCleanup(cls.cleanup)
        image=subprocess.check_output(['docker','image','inspect','postgres:16','--format','{{.Id}}'],text=True).strip()
        assert re.fullmatch(r'sha256:[a-f0-9]{64}',image)
        env=cls.root/'pg.env';env.write_text('POSTGRES_USER=synthetic\nPOSTGRES_DB=epharm_unified_candidate\nPOSTGRES_PASSWORD=synthetic-test-only\n');env.chmod(0o600)
        cls.container_id=subprocess.check_output(['docker','run','--pull=never','-d','--network=none','--name',cls.name,'--env-file',str(env),image],text=True).strip()
        for _ in range(100):
            p=subprocess.run(['docker','exec',cls.name,'pg_isready','-h','127.0.0.1','-U','synthetic'],capture_output=True)
            if p.returncode==0:break
            time.sleep(.1)
        cls.db=stage.Database(cls.name,'synthetic','epharm_unified_candidate');cls.target=cls.db.inspect()
        cls.db.run('''CREATE TABLE merch_organizations(id uuid PRIMARY KEY);
          CREATE TABLE merch_branches(id uuid PRIMARY KEY,organization_id uuid REFERENCES merch_organizations,epharm_pharmacy_id text,pharmacy_binding_verified boolean,active boolean);
          CREATE TABLE unified_import_runs(operation_key text PRIMARY KEY,source_sha256 text,identity_sha256 text,result_json jsonb);
          CREATE TABLE pharmacist_wallet_entries(id text PRIMARY KEY,amount bigint);
          INSERT INTO pharmacist_wallet_entries VALUES('sentinel',123);''')
        cls.db.run((ROOT/'admin-panel/backend/src/main/resources/db/migration/V089__native_recommendation_context.sql').read_text())

    @classmethod
    def cleanup(cls):
        if cls.container_id:subprocess.run(['docker','rm','-fv',cls.container_id],capture_output=True)
        cls.temp.cleanup()

    def prepare(self,empty=False):
        self.value=artifact(empty);raw=stage.encoded(self.value);self.digest=stage.digest(raw);self.schema='crm_stage_'+self.digest[:16]
        self.path=self.root/('snapshot-'+str(time.time_ns())+'.json');stage.private_write(self.path,raw)
        self.db.run('TRUNCATE merch_recommendation_signals,merch_product_balances,merch_branches,merch_organizations,unified_import_runs CASCADE;DROP SCHEMA IF EXISTS '+stage.qi(self.schema)+' CASCADE;')
        self.db.run(stage.load_sql(self.value,self.schema,self.digest))
        self.db.run("INSERT INTO merch_organizations VALUES('"+ORG+"');INSERT INTO merch_branches VALUES('"+BRANCH+"','"+ORG+"','exact-pharmacy',true,true);INSERT INTO unified_import_runs VALUES('owned_merch_extended:"+self.digest[:24]+"','"+self.digest+"','"+self.digest+"','{}');")

    def cli(self,mode='project',apply=False,raw=None,state=None,pin=None):
        report=self.root/('report-'+str(time.time_ns())+'.json')
        cmd=[sys.executable,str(TOOLS/'owned_context.py'),mode,'--container',self.name,'--user','synthetic','--database','epharm_unified_candidate','--artifact',str(self.path),'--artifact-sha256',self.digest,'--target-sha256',pin or self.target['identitySha256'],'--report',str(report)]
        if apply:cmd+=['--apply']
        if raw is not None:
            path=self.root/('input-'+str(time.time_ns())+'.json');stage.private_write(path,raw)
            cmd+=['--input',str(path),'--input-sha256',stage.digest(raw)]
            if state:cmd+=['--expected-context-sha256',state,'--confirm-pharmacy-count','1']
        result=subprocess.run(cmd,text=True,capture_output=True)
        combined=result.stdout+result.stderr+(report.read_text() if report.exists() else '')
        for secret in (ORG,BRANCH,SIGNAL,'SYNTHETIC-private-script','exact-offer'):self.assertNotIn(secret,combined)
        self.assertNotIn('Traceback',combined)
        if report.exists():self.assertEqual(report.stat().st_mode&0o777,0o600)
        return result

    def read(self,sql):return self.db.readonly('SET LOCAL search_path=pg_catalog,public;'+sql)

    def counts(self):return self.read("SELECT json_build_array((SELECT count(*) FROM merch_recommendation_signals),(SELECT count(*) FROM merch_product_balances),(SELECT amount FROM pharmacist_wallet_entries WHERE id='sentinel')); ").strip()

    def test_snapshot_exact_ids_fields_history_empty_and_repeat_without_money(self):
        self.prepare();dry=self.cli();self.assertEqual(dry.returncode,0,dry.stderr);self.assertEqual(json.loads(self.counts()),[0,0,123])
        first=self.cli(apply=True);self.assertEqual(first.returncode,0,first.stderr)
        self.assertEqual(json.loads(self.counts()),[1,1,123]);self.assertEqual(json.loads(self.cli(apply=True).stdout)['mode'],'verified_existing_noop')
        self.assertEqual(self.read('SELECT id::text||\':\'||pharmacy_available_qty::text FROM merch_product_balances;').strip(),'123:3.140')
        self.assertEqual(self.read('SELECT source_payload=to_jsonb(s) FROM merch_recommendation_signals t JOIN '+stage.qi(self.schema)+'.pharmapay_recommendation_signals s ON t.id=s.id;').strip(),'t')
        self.prepare(empty=True);self.assertEqual(self.cli(apply=True).returncode,0);self.assertEqual(json.loads(self.counts()),[0,0,123])

    def test_manual_dryrun_apply_cas_stale_repeat_preserves_ids_and_raw_provenance(self):
        self.prepare();self.assertEqual(self.cli(apply=True).returncode,0)
        before=self.read('SELECT '+context.context_hash()+';').strip()
        plan=self.cli('sync',raw=manual());self.assertEqual(plan.returncode,0,plan.stderr);self.assertEqual(before,self.read('SELECT '+context.context_hash()+';').strip())
        denied=self.cli('sync',apply=True,raw=manual(),state='f'*64);self.assertNotEqual(denied.returncode,0)
        done=self.cli('sync',apply=True,raw=manual(),state=before);self.assertEqual(done.returncode,0,done.stderr)
        self.assertEqual(self.read('SELECT id::text||\':\'||pharmacy_available_qty::text FROM merch_product_balances;').strip(),'123:4.000')
        after=self.read('SELECT '+context.context_hash()+';').strip()
        self.assertEqual(self.cli('sync',apply=True,raw=manual(),state=after).returncode,0)
        self.assertEqual(after,self.read('SELECT '+context.context_hash()+';').strip())
        stale=self.cli('sync',apply=True,raw=manual(2,'2026-10-29T00:00:00Z'),state=after);self.assertNotEqual(stale.returncode,0)
        self.assertEqual(after,self.read('SELECT '+context.context_hash()+';').strip())
        self.assertEqual(self.read('SELECT source_payload=to_jsonb(s) FROM merch_product_balances t JOIN '+stage.qi(self.schema)+'.pharmapay_product_balances s ON t.id=s.id;').strip(),'t')

    def test_manual_new_native_rows_keep_partial_semantics_and_same_timestamp_conflicts(self):
        self.prepare(empty=True);self.assertEqual(self.cli(apply=True).returncode,0)
        raw=json.dumps({'pharmacies':[{'pharmacyId':'exact-pharmacy','recommendations':[{'kind':'crosssell','triggerIpartId':'opaque-ipart','recommendBarcode':'4871234567890','bonus':120}],
            'balances':[{'barcode':'4871234567890','source':'reviewed-native','quality':'estimated','observedAt':'2026-10-30T00:00:00Z','warehouseAvailableQty':1.250}]}]}).encode()
        before=self.read('SELECT '+context.context_hash()+';').strip()
        result=self.cli('sync',apply=True,raw=raw,state=before);self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(self.counts()),[1,1,123])
        self.assertEqual(self.read("SELECT source_system||':'||warehouse_available_qty::text||':'||(pharmacy_available_qty IS NULL)::text FROM merch_product_balances;").strip(),'EPHARM:1.250:true')
        state=self.read('SELECT '+context.context_hash()+';').strip()
        conflict=json.loads(raw);conflict['pharmacies'][0]['balances'][0]['warehouseAvailableQty']=2
        self.assertNotEqual(self.cli('sync',apply=True,raw=json.dumps(conflict).encode(),state=state).returncode,0)
        self.assertEqual(state,self.read('SELECT '+context.context_hash()+';').strip())
        partial=json.dumps({'pharmacies':[{'pharmacyId':'exact-pharmacy','recommendations':[],'balances':[]}]}).encode()
        self.assertEqual(self.cli('sync',apply=True,raw=partial,state=state).returncode,0)
        self.assertEqual(json.loads(self.counts()),[1,1,123])

    def test_auto_uuid5_role_collision_across_files_requires_explicit_identity(self):
        self.prepare(empty=True);self.assertEqual(self.cli(apply=True).returncode,0)
        def payload(ipart=False,identity=None):
            row={'kind':'substitution','triggerIpartId' if ipart else 'triggerBarcode':'123',
                 'recommendIpartId' if ipart else 'recommendBarcode':'456'}
            if identity:row['id']=identity
            return json.dumps({'pharmacies':[{'pharmacyId':'exact-pharmacy','recommendations':[row]}]}).encode()
        initial=payload();state=self.read('SELECT '+context.context_hash()+';').strip()
        self.assertEqual(self.cli('sync',apply=True,raw=initial,state=state).returncode,0)
        state=self.read('SELECT '+context.context_hash()+';').strip()
        collision=self.cli('sync',apply=True,raw=payload(True),state=state)
        self.assertNotEqual(collision.returncode,0)
        self.assertEqual(state,self.read('SELECT '+context.context_hash()+';').strip())
        identity=context.load_manual(initial)[0][0]['id']
        edited=self.cli('sync',apply=True,raw=payload(True,identity),state=state)
        self.assertEqual(edited.returncode,0,edited.stderr)
        self.assertEqual(self.read("SELECT trigger_barcode IS NULL AND trigger_ipart_id='123' AND recommend_barcode IS NULL AND recommend_ipart_id='456' FROM merch_recommendation_signals;").strip(),'t')

    def test_parallel_disjoint_sync_rechecks_cas_after_waiting_for_first_writer(self):
        self.prepare();self.assertEqual(self.cli(apply=True).returncode,0)
        second=json.dumps({'pharmacies':[{'pharmacyId':'exact-pharmacy','balances':[{'externalKey':'second','productId':'second-offer','source':'reviewed','observedAt':'2026-10-30T01:00:00Z','pharmacyAvailableQty':2}]}]}).encode()
        state=self.read('SELECT '+context.context_hash()+';').strip()
        self.assertEqual(self.cli('sync',apply=True,raw=second,state=state).returncode,0)
        state=self.read('SELECT '+context.context_hash()+';').strip()
        first=subprocess.Popen(['docker','exec','-i',self.name,'psql','-XqAt','-U','synthetic','-d','epharm_unified_candidate','-v','ON_ERROR_STOP=1'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        first.stdin.write("BEGIN;SELECT pg_advisory_xact_lock(817106,9);SELECT 'LOCKED';\n");first.stdin.flush()
        while first.stdout.readline().strip()!='LOCKED':
            if first.poll() is not None:self.fail('fixture writer exited')
        newer=json.loads(second);newer['pharmacies'][0]['balances'][0].update(pharmacyAvailableQty=7,observedAt='2026-10-30T02:00:00Z')
        try:
            with ThreadPoolExecutor(max_workers=1) as pool:
                pending=pool.submit(self.cli,'sync',True,json.dumps(newer).encode(),state)
                for _ in range(200):
                    waiting=self.read("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=817106 AND objid=9 AND NOT granted;").strip()
                    if waiting=='1':break
                    time.sleep(.01)
                else:self.fail('second operator never reached gate')
                first.stdin.write("UPDATE public.merch_product_balances SET pharmacy_available_qty=5,observed_at='2026-10-30T02:00:00Z' WHERE id=123;COMMIT;\n");first.stdin.flush();first.stdin.close()
                self.assertEqual(first.wait(timeout=10),0)
                result=pending.result(timeout=20)
                self.assertNotEqual(result.returncode,0)
        finally:
            if first.poll() is None:first.terminate();first.wait(timeout=10)
            first.stdout.close();first.stderr.close()
        self.assertEqual(self.read("SELECT pharmacy_available_qty::text FROM merch_product_balances WHERE external_key='second';").strip(),'2.000')

    def test_projection_repeat_sees_manual_changes_committed_while_waiting_at_gate(self):
        self.prepare();self.assertEqual(self.cli(apply=True).returncode,0)
        writer=subprocess.Popen(['docker','exec','-i',self.name,'psql','-XqAt','-U','synthetic','-d','epharm_unified_candidate','-v','ON_ERROR_STOP=1'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        writer.stdin.write("BEGIN;SELECT pg_advisory_xact_lock(817106,9);SELECT 'LOCKED';\n");writer.stdin.flush()
        while writer.stdout.readline().strip()!='LOCKED':
            if writer.poll() is not None:self.fail('fixture writer exited')
        try:
            with ThreadPoolExecutor(max_workers=1) as pool:
                pending=pool.submit(self.cli,'project',True)
                for _ in range(200):
                    if self.read("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=817106 AND objid=9 AND NOT granted;").strip()=='1':break
                    time.sleep(.01)
                else:self.fail('projection did not reach gate')
                writer.stdin.write("UPDATE public.merch_recommendation_signals SET script='reviewed-manual-change';COMMIT;\n");writer.stdin.flush();writer.stdin.close()
                self.assertEqual(writer.wait(timeout=10),0)
                self.assertNotEqual(pending.result(timeout=20).returncode,0)
        finally:
            if writer.poll() is None:writer.terminate();writer.wait(timeout=10)
            writer.stdout.close();writer.stderr.close()
        self.assertEqual(self.read('SELECT script FROM merch_recommendation_signals;').strip(),'reviewed-manual-change')
        self.assertEqual(json.loads(self.counts()),[1,1,123])

    def test_wrong_pin_source_drift_and_missing_dependency_fail_atomically(self):
        self.prepare();self.assertNotEqual(self.cli(apply=True,pin='f'*64).returncode,0)
        self.db.run('UPDATE '+stage.qi(self.schema)+".preserved_00 SET id='unexpected';INSERT INTO "+stage.qi(self.schema)+".preserved_00 VALUES('drift');")
        self.assertNotEqual(self.cli(apply=True).returncode,0);self.assertEqual(json.loads(self.counts()),[0,0,123])
        self.prepare();self.db.run('DELETE FROM merch_branches;')
        self.assertNotEqual(self.cli(apply=True).returncode,0);self.assertEqual(json.loads(self.counts()),[0,0,123])

if __name__=='__main__':unittest.main()
