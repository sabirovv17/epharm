#!/usr/bin/env python3
"""Candidate-only exact-key context projection and reviewed manual sync. No scheduler/API/rewards."""
import argparse
from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid

import crm_stage as stage
import owned_automation
import owned_merch as owned

SIGNALS='id organization_id branch_id kind trigger_barcode trigger_ipart_id recommend_product_id recommend_barcode recommend_ipart_id script advantages partner_label bonus priority active valid_from valid_until created_at updated_at'.split()
BALANCES='id organization_id branch_id external_key product_id barcode ipart_id pharmacy_available_qty network_available_qty warehouse_available_qty reserved_qty quality source observed_at updated_at'.split()
TABLES={'merch_recommendation_signals':('pharmapay_recommendation_signals',SIGNALS), 'merch_product_balances':('pharmapay_product_balances',BALANCES)}
NAMESPACE=uuid.UUID('fe6a7c4e-9235-4d73-b163-0a8968d51126')
SAFE=re.compile(r'[0-9A-Za-z._:+@/\-]+\Z')
LOCK='SELECT pg_advisory_lock(817106,9);'


def context_hash():
    return "(SELECT encode(sha256(convert_to(jsonb_build_object('signals',(SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY id),'[]'::jsonb) FROM public.merch_recommendation_signals s),'balances',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY id),'[]'::jsonb) FROM public.merch_product_balances b))::text,'UTF8')),'hex'))"


def projection_sql(schema,source_hash,verification,apply=True):
    stage.require(schema=='crm_stage_'+stage.hash_arg(source_hash)[:16],'untrusted staging namespace')
    q=stage.qi(schema);key='owned_merch_context:'+source_hash[:24];done='(SELECT done FROM _context_replay)'
    # A repeat must observe manual changes committed while it waited at the gate.
    sql=[LOCK,'BEGIN ISOLATION LEVEL REPEATABLE READ;',stage.SETTINGS,verification,
         owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_extended:"+source_hash[:24]+"' AND source_sha256="+stage.literal(source_hash)+")",'verified extended source projection required'),
         'CREATE TEMP TABLE _context_replay AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='+stage.literal(key)+' AND source_sha256='+stage.literal(source_hash)+' AND identity_sha256='+stage.literal(source_hash)+') AS done;']
    for target,(source,fields) in TABLES.items():
        mapping={name:'s.'+name for name in fields}
        mapping.update(source_system="'MERCH_CRM'",source_origin="'https://crm.inkar.kz'",source_id='s.id',source_payload='to_jsonb(s)')
        same=' AND '.join('t.'+stage.qi(k)+' IS NOT DISTINCT FROM ('+v+')' for k,v in mapping.items())
        sql += [owned.assertion('NOT EXISTS(SELECT 1 FROM '+q+'.'+source+' s LEFT JOIN public.merch_organizations o ON o.id=s.organization_id LEFT JOIN public.merch_branches b ON b.id=s.branch_id WHERE o.id IS NULL OR (s.branch_id IS NOT NULL AND (b.id IS NULL OR b.organization_id IS DISTINCT FROM s.organization_id)))','missing or conflicting context scope'),
                owned.assertion(done+' OR NOT EXISTS(SELECT 1 FROM public.'+target+')','context target not empty'),
                'INSERT INTO public.'+target+'('+','.join(map(stage.qi,mapping))+') SELECT '+','.join(mapping.values())+' FROM '+q+'.'+source+' s WHERE NOT '+done+';',
                owned.assertion('(SELECT count(*) FROM public.'+target+')=(SELECT count(*) FROM '+q+'.'+source+') AND NOT EXISTS(SELECT 1 FROM '+q+'.'+source+' s LEFT JOIN public.'+target+' t ON t.id=s.id WHERE t.id IS NULL OR NOT('+same+'))','context source fields differ')]
    sql += ['INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT '+','.join(map(stage.literal,[key,source_hash,source_hash]))+",'{}'::jsonb WHERE NOT "+done+';',
            "SELECT json_build_object('mode',CASE WHEN "+done+" THEN 'verified_existing_noop' ELSE "+stage.literal('materialized_and_verified' if apply else 'dry_run_rolled_back')+" END,'signals',(SELECT count(*) FROM public.merch_recommendation_signals),'balances',(SELECT count(*) FROM public.merch_product_balances),'sourceRowsPreserved',true,'newRewards',0,'newJobs',0);",'COMMIT;' if apply else 'ROLLBACK;','SELECT pg_advisory_unlock(817106,9);']
    return '\n'.join(sql)


def ref(value,limit,required=False):
    stage.require(value is None or isinstance(value,str),'identifier must be text')
    v=(value or '').strip() or None
    stage.require(not required or v is not None,'required exact identifier missing')
    stage.require(v is None or len(v)<=limit and SAFE.fullmatch(v),'invalid exact identifier')
    return v


def timestamp(value,required=False):
    stage.require(value is None or isinstance(value,str),'timestamp must be text')
    stage.require(not required or bool(value),'timestamp required')
    if value:
        parsed=datetime.fromisoformat(value.replace('Z','+00:00'));stage.require(parsed.tzinfo is not None,'timestamp requires timezone')
    return value


def load_manual(raw):
    root=json.loads(raw,parse_float=Decimal,object_pairs_hook=stage.unique_object,
                    parse_constant=lambda _:stage.require(False,'nonfinite JSON'))
    pharmacies=root.get('pharmacies') if isinstance(root,dict) else None
    stage.require(isinstance(root,dict) and set(root)=={'pharmacies'} and isinstance(pharmacies,list) and 0<len(pharmacies)<=10000,'pharmacies array required')
    signals=[];balances=[];seen=set();ids=set();keys=set()
    for p in pharmacies:
        stage.require(isinstance(p,dict) and set(p)<= {'pharmacyId','recommendations','balances'},'invalid pharmacy');pharmacy=ref(p.get('pharmacyId'),128,True)
        stage.require(pharmacy not in seen,'duplicate pharmacy');seen.add(pharmacy)
        for field in ('recommendations','balances'):stage.require(isinstance(p.get(field,[]),list),'invalid feed array')
        for item in p.get('recommendations',[]):
            stage.require(isinstance(item,dict) and set(item)<= {'id','kind','triggerBarcode','triggerIpartId','recommendProductId','recommendBarcode','recommendIpartId','script','advantages','partnerLabel','bonus','priority','validFrom','validUntil'},'invalid recommendation')
            kind=item.get('kind');stage.require(kind in ('substitution','crosssell'),'invalid kind')
            fields={k:ref(item.get(k),32 if 'Barcode' in k else 64) for k in ('triggerBarcode','triggerIpartId','recommendProductId','recommendBarcode','recommendIpartId')}
            stage.require(fields['triggerBarcode'] or fields['triggerIpartId'],'exact trigger missing')
            stage.require(any(fields[k] for k in ('recommendProductId','recommendBarcode','recommendIpartId')),'exact product missing')
            stable='|'.join(filter(None,[pharmacy,kind,*fields.values()]))
            identity=str(uuid.UUID(item['id'])) if item.get('id') else str(uuid.uuid5(NAMESPACE,stable))
            stage.require(identity not in ids,'duplicate signal ID');ids.add(identity)
            script=item.get('script') or '';label=item.get('partnerLabel') or None;advantages=item.get('advantages') or []
            stage.require(isinstance(script,str) and len(script)<=1200 and (label is None or isinstance(label,str) and len(label)<=80),'invalid recommendation text')
            stage.require(isinstance(advantages,list) and len(advantages)<=12 and all(isinstance(a,str) and len(a)<=240 for a in advantages),'invalid advantages')
            bonus=item.get('bonus',0);priority=item.get('priority',0)
            stage.require(type(bonus) is int and 0<=bonus<=1000000 and type(priority) is int and -1000<=priority<=1000,'invalid bonus/priority')
            start=timestamp(item.get('validFrom'));end=timestamp(item.get('validUntil'))
            stage.require(not start or not end or datetime.fromisoformat(end.replace('Z','+00:00'))>datetime.fromisoformat(start.replace('Z','+00:00')),'invalid window')
            signals.append(dict(id=identity,explicitId=bool(item.get('id')),pharmacy=pharmacy,kind=kind,**fields,script=script,advantages=advantages,partnerLabel=label,bonus=bonus,priority=priority,validFrom=start,validUntil=end))
        for item in p.get('balances',[]):
            stage.require(isinstance(item,dict) and set(item)<= {'externalKey','productId','barcode','ipartId','pharmacyAvailableQty','networkAvailableQty','warehouseAvailableQty','reservedQty','quality','source','observedAt'},'invalid balance')
            fields={k:ref(item.get(k),32 if k=='barcode' else 64) for k in ('productId','barcode','ipartId')}
            stage.require(any(fields.values()),'exact product missing')
            key=ref(item.get('externalKey') or ('product:'+fields['productId'] if fields['productId'] else 'ean:'+fields['barcode'] if fields['barcode'] else 'ipart:'+fields['ipartId']),160,True)
            stage.require((pharmacy,key) not in keys,'duplicate balance key');keys.add((pharmacy,key))
            quality=item.get('quality','confirmed');source=item.get('source')
            stage.require(quality in ('confirmed','estimated') and isinstance(source,str) and 0<len(source)<=80,'quality/source required')
            quantities={}
            for k in ('pharmacyAvailableQty','networkAvailableQty','warehouseAvailableQty','reservedQty'):
                n=item.get(k)
                stage.require(n is None or type(n) in (int,Decimal),'quantity must be numeric or null')
                d=None if n is None else Decimal(n)
                stage.require(d is None or d.is_finite() and 0<=d<10**11 and d==d.quantize(Decimal('.001')),'invalid quantity')
                quantities[k]=None if d is None else str(d)
            balances.append(dict(pharmacy=pharmacy,externalKey=key,**fields,**quantities,quality=quality,source=source,observedAt=timestamp(item.get('observedAt'),True)))
    stage.require(len(signals)+len(balances)<=100000,'feed too large')
    return signals,balances,len(seen)


def manual_sql(schema,source_hash,verification,raw,expected_hash,apply=False,expected_count=None):
    stage.require(schema=='crm_stage_'+stage.hash_arg(source_hash)[:16],'untrusted staging namespace')
    signals,balances,count=load_manual(raw);digest=stage.digest(raw)
    if apply:stage.require(expected_count==count,'exact confirmed pharmacy count required');stage.hash_arg(expected_hash)
    key='owned_context_sync:'+digest[:24]
    # A session gate is acquired BEFORE the RR snapshot: waiting after BEGIN/SELECT
    # would allow stale CAS on disjoint concurrent updates. psql exit releases it on every error.
    sql=[LOCK,'BEGIN ISOLATION LEVEL REPEATABLE READ;',stage.SETTINGS,verification,
         owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_context:"+source_hash[:24]+"' AND source_sha256="+stage.literal(source_hash)+")",'verified context projection required'),
         'CREATE TEMP TABLE _before_context AS SELECT '+context_hash()+' AS hash;',
         'CREATE TEMP TABLE _sync_signals AS SELECT * FROM jsonb_to_recordset('+stage.literal(json.dumps(signals))+'''::jsonb) AS i(id uuid,"explicitId" boolean,pharmacy text,kind text,"triggerBarcode" text,"triggerIpartId" text,"recommendProductId" text,"recommendBarcode" text,"recommendIpartId" text,script text,advantages jsonb,"partnerLabel" text,bonus integer,priority integer,"validFrom" timestamptz,"validUntil" timestamptz);''',
         'CREATE TEMP TABLE _sync_balances AS SELECT * FROM jsonb_to_recordset('+stage.literal(json.dumps(balances))+'''::jsonb) AS i(pharmacy text,"externalKey" text,"productId" text,barcode text,"ipartId" text,"pharmacyAvailableQty" numeric,"networkAvailableQty" numeric,"warehouseAvailableQty" numeric,"reservedQty" numeric,quality text,source text,"observedAt" timestamptz);''',
         'CREATE TEMP TABLE _sync_scopes AS SELECT p.pharmacy,b.id AS branch_id,b.organization_id FROM (SELECT pharmacy FROM _sync_signals UNION SELECT pharmacy FROM _sync_balances) p JOIN public.merch_branches b ON b.epharm_pharmacy_id=p.pharmacy AND b.pharmacy_binding_verified AND b.active;',
         owned.assertion('NOT EXISTS(SELECT 1 FROM (SELECT pharmacy FROM _sync_signals UNION SELECT pharmacy FROM _sync_balances) p LEFT JOIN _sync_scopes s USING(pharmacy) GROUP BY p.pharmacy HAVING count(s.branch_id)<>1)','missing or ambiguous verified pharmacy'),
         owned.assertion('NOT EXISTS(SELECT 1 FROM _sync_signals i JOIN _sync_scopes s USING(pharmacy) JOIN public.merch_recommendation_signals t ON t.id=i.id WHERE t.branch_id IS DISTINCT FROM s.branch_id OR t.organization_id IS DISTINCT FROM s.organization_id)','signal identity scope conflict'),
         owned.assertion('NOT EXISTS(SELECT 1 FROM _sync_balances i JOIN _sync_scopes s USING(pharmacy) JOIN public.merch_product_balances t ON t.branch_id=s.branch_id AND t.external_key=i."externalKey" WHERE i."observedAt"<t.observed_at)','stale observation overwrite refused')]
    identity_map={'kind':'i.kind','trigger_barcode':'i."triggerBarcode"','trigger_ipart_id':'i."triggerIpartId"',
                  'recommend_product_id':'i."recommendProductId"','recommend_barcode':'i."recommendBarcode"','recommend_ipart_id':'i."recommendIpartId"'}
    identity_equal=' AND '.join('t.'+k+' IS NOT DISTINCT FROM ('+v+')' for k,v in identity_map.items())
    sql.append(owned.assertion('NOT EXISTS(SELECT 1 FROM _sync_signals i JOIN public.merch_recommendation_signals t ON t.id=i.id WHERE NOT i."explicitId" AND NOT('+identity_equal+'))','generated ID collides across exact product roles; explicit ID required'))
    if apply:sql.append(owned.assertion('(SELECT hash FROM _before_context)='+stage.literal(expected_hash),'context CAS changed'))
    # Original source_payload/namespace/IDs stay immutable on updates; every reviewed
    # input is appended in unified_import_runs, never erased or replayed as money.
    signal_map={'kind':'i.kind','trigger_barcode':'i."triggerBarcode"','trigger_ipart_id':'i."triggerIpartId"','recommend_product_id':'i."recommendProductId"','recommend_barcode':'i."recommendBarcode"','recommend_ipart_id':'i."recommendIpartId"','script':'i.script','advantages':'i.advantages','partner_label':'i."partnerLabel"','bonus':'i.bonus','priority':'i.priority','active':'true','valid_from':'i."validFrom"','valid_until':'i."validUntil"'}
    balance_map={'product_id':'i."productId"','barcode':'i.barcode','ipart_id':'i."ipartId"','pharmacy_available_qty':'i."pharmacyAvailableQty"','network_available_qty':'i."networkAvailableQty"','warehouse_available_qty':'i."warehouseAvailableQty"','reserved_qty':'i."reservedQty"','quality':'i.quality','source':'i.source','observed_at':'i."observedAt"'}
    signal_equal=' AND '.join('t.'+k+' IS NOT DISTINCT FROM ('+v+')' for k,v in signal_map.items())
    balance_equal=' AND '.join('t.'+k+' IS NOT DISTINCT FROM ('+v+')' for k,v in balance_map.items())
    sql += [owned.assertion('NOT EXISTS(SELECT 1 FROM _sync_balances i JOIN _sync_scopes s USING(pharmacy) JOIN public.merch_product_balances t ON t.branch_id=s.branch_id AND t.external_key=i."externalKey" WHERE i."observedAt"=t.observed_at AND NOT('+balance_equal+'))','same observation conflicts'),
            owned.assertion('NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='+stage.literal(key)+') OR (NOT EXISTS(SELECT 1 FROM _sync_signals i LEFT JOIN public.merch_recommendation_signals t ON t.id=i.id WHERE t.id IS NULL OR NOT('+signal_equal+')) AND NOT EXISTS(SELECT 1 FROM _sync_balances i JOIN _sync_scopes s USING(pharmacy) LEFT JOIN public.merch_product_balances t ON t.branch_id=s.branch_id AND t.external_key=i."externalKey" WHERE t.id IS NULL OR NOT('+balance_equal+')))','previous sync no longer matches current state'),
            'UPDATE public.merch_recommendation_signals t SET '+','.join(k+'='+v for k,v in signal_map.items())+',updated_at=now() FROM _sync_signals i WHERE t.id=i.id AND NOT('+signal_equal+');',
            "INSERT INTO public.merch_recommendation_signals(id,source_system,source_origin,source_id,source_payload,organization_id,branch_id,"+','.join(signal_map)+") SELECT i.id,'EPHARM','https://epharm.inkar.kz',i.id,to_jsonb(i),s.organization_id,s.branch_id,"+','.join(signal_map.values())+' FROM _sync_signals i JOIN _sync_scopes s USING(pharmacy) WHERE NOT EXISTS(SELECT 1 FROM public.merch_recommendation_signals t WHERE t.id=i.id);',
            'UPDATE public.merch_product_balances t SET '+','.join(k+'='+v for k,v in balance_map.items())+',updated_at=now() FROM _sync_balances i JOIN _sync_scopes s USING(pharmacy) WHERE t.branch_id=s.branch_id AND t.external_key=i."externalKey" AND NOT('+balance_equal+');',
            'CREATE TEMP TABLE _new_balances AS SELECT i.*,s.branch_id,s.organization_id,(SELECT coalesce(max(id),0) FROM public.merch_product_balances)+row_number() OVER(ORDER BY s.branch_id,i."externalKey") AS id FROM _sync_balances i JOIN _sync_scopes s USING(pharmacy) WHERE NOT EXISTS(SELECT 1 FROM public.merch_product_balances t WHERE t.branch_id=s.branch_id AND t.external_key=i."externalKey");',
            "INSERT INTO public.merch_product_balances(id,source_system,source_origin,source_id,source_payload,organization_id,branch_id,external_key,"+','.join(balance_map)+") SELECT i.id,'EPHARM','https://epharm.inkar.kz',i.id,to_jsonb(i),i.organization_id,i.branch_id,i.\"externalKey\","+','.join(balance_map.values())+' FROM _new_balances i;',
            'INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT '+','.join(map(stage.literal,[key,source_hash,digest]))+",jsonb_build_object('inputSha256',"+stage.literal(digest)+",'reviewedInput',"+stage.literal(raw.decode())+"::jsonb,'beforeContextSha256',(SELECT hash FROM _before_context)) WHERE NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key="+stage.literal(key)+');',
            "SELECT json_build_object('mode',"+stage.literal('applied_verified_input' if apply else 'dry_run_rolled_back')+",'pharmacies',"+str(count)+",'signals',"+str(len(signals))+",'balances',"+str(len(balances))+",'inputSha256',"+stage.literal(digest)+",'beforeContextSha256',(SELECT hash FROM _before_context),'afterContextSha256',"+context_hash()+",'newRewards',0,'newJobs',0);",'COMMIT;' if apply else 'ROLLBACK;','SELECT pg_advisory_unlock(817106,9);']
    return '\n'.join(sql)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['project','sync'])
    for name in ('container','user','database','artifact-sha256','target-sha256'):p.add_argument('--'+name,required=True)
    for name in ('artifact','report'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--apply',action='store_true');p.add_argument('--input',type=Path);p.add_argument('--input-sha256')
    p.add_argument('--expected-context-sha256');p.add_argument('--confirm-pharmacy-count',type=int)
    a=p.parse_args()
    try:
        if a.mode=='project':project=lambda s,h,v:projection_sql(s,h,v,a.apply)
        else:
            stage.require(a.input is not None,'reviewed input required')
            with os.fdopen(os.open(a.input,os.O_RDONLY|os.O_NOFOLLOW),'rb') as f:
                info=os.fstat(f.fileno());stage.require(stat.S_ISREG(info.st_mode) and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode) in (0o400,0o600) and info.st_size<=32*1024*1024,'private bounded reviewed input required');raw=f.read(32*1024*1024+1)
            stage.require(stage.digest(raw)==stage.hash_arg(a.input_sha256),'input pin mismatch')
            project=lambda s,h,v:manual_sql(s,h,v,raw,a.expected_context_sha256,a.apply,a.confirm_pharmacy_count)
        print(json.dumps(owned_automation.run(a,project),sort_keys=True))
    except (stage.StageError,ValueError,KeyError,TypeError,OSError,IndexError,InvalidOperation):
        print('Context operation refused; private source values suppressed.',file=sys.stderr);raise SystemExit(1)


if __name__=='__main__':main()
