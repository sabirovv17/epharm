#!/usr/bin/env python3
"""Project verified commercial snapshots after V078–V080; never submit, send or pay."""
import argparse
import json
from pathlib import Path
import sys

import crm_stage as stage
import owned_automation
import owned_merch as owned


def projections():
    lineage = dict(source_system="'MERCH_CRM'", source_origin=stage.literal(owned.ORIGIN),
                   source_id='s.id', source_payload='to_jsonb(s)')
    def identity(fields):
        return {name:'s.'+name for name in fields.split()}
    return [
        ('merch_supplier_pricing_tiers','supplier_pricing_tiers','category',dict(
            identity('category label price_multiplier facing_month_price promotion_month_price training_event_price motivation_person_price active updated_at'),
            source_payload='to_jsonb(s)')),
        ('merch_supplier_settings','advertiser_profiles','producer_id',dict(
            producer_id='s.user_id',supplier_category='s.supplier_category',six_month_budget='s.six_month_budget',
            primary_contact_role='s.primary_contact_role',source_payload='to_jsonb(s)',updated_at='s.updated_at')),
        ('merch_supplier_company_members','supplier_company_members','id',dict(
            lineage,**identity('id full_name email position access_role active created_at updated_at'),producer_id='s.supplier_user_id')),
        ('merch_supplier_budget_plans','supplier_budget_plans','id',dict(
            lineage,**identity('id title start_month end_month budget_limit status notes planning_mode product_briefs shelf_selection extra_activities request_ids created_at updated_at'),
            producer_id='s.supplier_user_id')),
        ('merch_supplier_budget_plan_items','supplier_budget_plan_items','id',dict(
            lineage,**identity('id plan_id product_name sku activity_type active_months pharmacy_count facing_count people_count unit_price supplier_category note position_index created_at'))),
        ('merch_placement_item_product_details','placement_item_product_details','media_plan_item_id',dict(
            lineage,**identity('media_plan_item_id product_name sku facing_count unit_price base_shelf_price supplier_category products created_at'),source_id='s.media_plan_item_id')),
        ('merch_task_source_stock','staff_task_stock_observations','item_id',dict(
            item_id='s.task_id',counts='s.counts',shortage_reason="coalesce(s.shortage_reason,'')",source_payload='to_jsonb(s)',
            created_at='s.updated_at',updated_at='s.updated_at')),
    ]


def projection_sql(schema, source_hash, verification):
    stage.require(schema == 'crm_stage_'+stage.hash_arg(source_hash)[:16],'untrusted staging namespace')
    q=stage.qi(schema);key='owned_merch_extended:'+source_hash[:24];done='(SELECT done FROM _extended_replay)'
    sql=['BEGIN ISOLATION LEVEL REPEATABLE READ;',stage.SETTINGS,'SELECT pg_advisory_xact_lock(817106,6);',verification,
         owned.assertion("EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='owned_merch_automation:"+source_hash[:24]+"' AND source_sha256="+stage.literal(source_hash)+")",'verified weekly projection required'),
         owned.assertion("NOT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key="+stage.literal(key)+' AND (source_sha256 IS DISTINCT FROM '+stage.literal(source_hash)+' OR identity_sha256 IS DISTINCT FROM '+stage.literal(source_hash)+'))','extended provenance differs'),
         'CREATE TEMP TABLE _extended_replay AS SELECT EXISTS(SELECT 1 FROM public.unified_import_runs WHERE operation_key='+stage.literal(key)+') AS done;']
    # These three source sets are empty in the approved snapshot. Nonempty inputs require explicit private-file lineage.
    for name in ('branch_passport_photos','advertising_place_photos','pharmacy_training_materials','staff_task_comments'):
        sql.append(owned.assertion('NOT EXISTS(SELECT 1 FROM '+q+'.'+name+')','private media projection requires verified object mapping'))
    for target,source,pk,fields in projections():
        names=','.join(map(stage.qi,fields));values=','.join(fields.values())
        same=' AND '.join('t.'+stage.qi(name)+' IS NOT DISTINCT FROM ('+value+')' for name,value in fields.items())
        sql += [owned.assertion(done+' OR NOT EXISTS(SELECT 1 FROM public.'+target+')','extended owned target is not empty'),
                'INSERT INTO public.'+target+'('+names+') SELECT '+values+' FROM '+q+'.'+source+' s WHERE NOT '+done+';',
                owned.assertion('(SELECT count(*) FROM public.'+target+')=(SELECT count(*) FROM '+q+'.'+source+') AND NOT EXISTS(SELECT 1 FROM '+q+'.'+source+' s LEFT JOIN public.'+target+' t ON t.'+pk+'='+fields[pk]+' WHERE t.'+pk+' IS NULL OR NOT('+same+'))','extended source fields differ')]
    sql += ['CREATE TEMP TABLE _source_campaigns AS SELECT s.campaign_id AS id,jsonb_build_object(\'sourceCampaignId\',s.campaign_id,\'sourcePlans\',jsonb_agg(to_jsonb(s) ORDER BY s.id)) AS provenance FROM '+q+'.media_plans s WHERE s.campaign_id IS NOT NULL GROUP BY s.campaign_id;',
            owned.assertion(done+' OR NOT EXISTS(SELECT 1 FROM public.merch_placement_campaigns)','placement campaign target is not empty'),
            "INSERT INTO public.merch_placement_campaigns(id,source_system,source_origin,source_id,provenance) SELECT id,'MERCH_CRM','https://crm.inkar.kz',id,provenance FROM _source_campaigns WHERE NOT "+done+';',
            owned.assertion('NOT EXISTS(SELECT 1 FROM _source_campaigns s FULL JOIN public.merch_placement_campaigns t ON t.id=s.id WHERE s.id IS NULL OR t.id IS NULL OR t.source_system IS DISTINCT FROM \'MERCH_CRM\' OR t.source_origin IS DISTINCT FROM \'https://crm.inkar.kz\' OR t.source_id IS DISTINCT FROM s.id OR t.provenance IS DISTINCT FROM s.provenance)','placement campaign namespace differs'),
            'CREATE TEMP TABLE _commercial_links AS SELECT r AS request_id,b.id AS budget_id FROM '+q+'.supplier_budget_plans b CROSS JOIN LATERAL unnest(b.request_ids) r JOIN '+q+'.media_plans p ON p.id=r AND p.submitted_by=b.supplier_user_id;',
            owned.assertion('NOT EXISTS(SELECT 1 FROM _commercial_links GROUP BY request_id HAVING count(*)>1)','ambiguous source budget link'),
            'UPDATE public.merch_media_plans t SET source_campaign_id=s.campaign_id,campaign_id=s.campaign_id,commercial_plan_id=(SELECT budget_id FROM _commercial_links WHERE request_id=s.id) FROM '+q+'.media_plans s WHERE t.id=s.id AND NOT '+done+';',
            owned.assertion('NOT EXISTS(SELECT 1 FROM '+q+'.media_plans s LEFT JOIN public.merch_media_plans t ON t.id=s.id WHERE t.source_system IS DISTINCT FROM \'MERCH_CRM\' OR t.source_payload IS DISTINCT FROM to_jsonb(s) OR t.source_campaign_id IS DISTINCT FROM s.campaign_id OR t.campaign_id IS DISTINCT FROM s.campaign_id OR t.commercial_plan_id IS DISTINCT FROM (SELECT budget_id FROM _commercial_links WHERE request_id=s.id))','typed placement/budget source link differs')]
    # V078 also runs before the first M0 import in a new installation: project late imports explicitly.
    floor=dict(floor_plan="s.floor_plan",floor_plan_draft="s.floor_plan_draft",
               traffic="jsonb_build_object('otsPerDay',s.traffic_ots_per_day,'vehicleTrafficPerDay',s.vehicle_traffic_per_day,'pedestrianTrafficPerDay',s.pedestrian_traffic_per_day,'source',s.traffic_source,'measuredAt',s.traffic_measured_at)")
    branch_join=' FROM '+q+'.branches s JOIN public.merch_branches t ON t.id=s.id'
    sql += [owned.assertion('NOT EXISTS(SELECT 1'+branch_join+" WHERE t.source_system<>'MERCH_CRM' OR t.source_payload IS DISTINCT FROM to_jsonb(s) OR t.editor_revision<>1)",'floor plan source or revision changed'),
            'UPDATE public.merch_branches t SET '+','.join(k+'='+v for k,v in floor.items())+' FROM '+q+'.branches s WHERE t.id=s.id AND NOT '+done+';',
            owned.assertion('NOT EXISTS(SELECT 1'+branch_join+' WHERE NOT('+' AND '.join('t.'+k+' IS NOT DISTINCT FROM ('+v+')' for k,v in floor.items())+'))','floor plan projection differs'),
            'INSERT INTO public.unified_import_runs(operation_key,source_sha256,identity_sha256,result_json) SELECT '+','.join(map(stage.literal,[key,source_hash,source_hash]))+",'{}'::jsonb WHERE NOT "+done+';',
            "SELECT json_build_object('mode',CASE WHEN "+done+" THEN 'verified_existing_noop' ELSE 'materialized_and_verified' END,'projectedRecords',"+
            '+'.join('(SELECT count(*) FROM public.'+t+')' for t,_,_,_ in projections())+",'placementCampaigns',(SELECT count(*) FROM _source_campaigns),'verifiedBudgetLinks',(SELECT count(*) FROM _commercial_links),'unresolvedBudgetReferences',(SELECT count(*) FROM "+q+".supplier_budget_plans b CROSS JOIN LATERAL unnest(b.request_ids) r LEFT JOIN _commercial_links l ON l.budget_id=b.id AND l.request_id=r WHERE l.request_id IS NULL),'generatedTasks',0,'issuedLinks',0,'newRewards',0);",'COMMIT;']
    return '\n'.join(sql)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('container','user','database','artifact-sha256','target-sha256'):parser.add_argument('--'+name,required=True)
    for name in ('artifact','report'):parser.add_argument('--'+name,type=Path,required=True)
    try:print(json.dumps(owned_automation.run(parser.parse_args(),projection_sql),sort_keys=True))
    except (stage.StageError,ValueError,KeyError,TypeError,OSError,IndexError):
        print('Extended projection refused; private source values suppressed.',file=sys.stderr);raise SystemExit(1)


if __name__=='__main__':main()
