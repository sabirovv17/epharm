-- Source contracts: CRM 067/070/071/072/076/077. Snapshot lineage remains private and never grants identity.
ALTER TABLE merch_media_plan_items DROP CONSTRAINT merch_media_plan_items_media_plan_id_shelf_id_key;
CREATE UNIQUE INDEX ux_merch_plan_shelf_portion ON merch_media_plan_items(media_plan_id,shelf_id,coalesce(sale_portion,'whole'));

CREATE TABLE merch_placement_item_product_details (
    media_plan_item_id uuid PRIMARY KEY REFERENCES merch_media_plan_items(id) ON DELETE RESTRICT,
    source_system varchar(32) NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    product_name text,sku text,facing_count integer NOT NULL CHECK(facing_count BETWEEN 1 AND 100),
    unit_price bigint CHECK(unit_price>=0),base_shelf_price bigint CHECK(base_shelf_price>=0),
    supplier_category text,products jsonb CHECK(products IS NULL OR jsonb_typeof(products)='array'),created_at timestamptz NOT NULL,
    CHECK(source_id=media_plan_item_id AND ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
        (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz')))
);

CREATE TABLE merch_supplier_pricing_tiers (
    category text PRIMARY KEY CHECK(category IN ('standard','big_pharma','india','local','distributor')),
    label text NOT NULL,price_multiplier numeric(6,3) NOT NULL CHECK(price_multiplier BETWEEN 0.1 AND 10),
    facing_month_price bigint NOT NULL CHECK(facing_month_price>=0),promotion_month_price bigint NOT NULL CHECK(promotion_month_price>=0),
    training_event_price bigint NOT NULL CHECK(training_event_price>=0),motivation_person_price bigint NOT NULL CHECK(motivation_person_price>=0),
    active boolean NOT NULL,updated_at timestamptz NOT NULL,source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object')
);
-- No tariffs are seeded from an old migration: the verified current source tariff rows must be projected explicitly.
CREATE TABLE merch_supplier_settings (
    producer_id uuid PRIMARY KEY REFERENCES merch_producers(id) ON DELETE RESTRICT,
    supplier_category text CHECK(supplier_category IN ('standard','big_pharma','india','local','distributor')),
    six_month_budget bigint CHECK(six_month_budget>=0),primary_contact_role text,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    updated_at timestamptz NOT NULL
);
CREATE TABLE merch_supplier_company_members (
    id uuid PRIMARY KEY,producer_id uuid NOT NULL REFERENCES merch_producers(id) ON DELETE RESTRICT,
    source_system varchar(32) NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),source_origin text NOT NULL,
    source_id uuid NOT NULL,source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    full_name text NOT NULL,email text NOT NULL,position text,access_role text NOT NULL CHECK(access_role IN ('head','planner','finance','viewer')),
    active boolean NOT NULL,revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),last_request_hash varchar(64),
    created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
    UNIQUE(producer_id,email),CHECK(source_id=id AND ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
        (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz')))
);
CREATE TABLE merch_supplier_budget_plans (
    id uuid PRIMARY KEY,producer_id uuid NOT NULL REFERENCES merch_producers(id) ON DELETE RESTRICT,
    source_system varchar(32) NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),source_origin text NOT NULL,
    source_id uuid NOT NULL,source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    title text NOT NULL,start_month date NOT NULL,end_month date NOT NULL,budget_limit bigint NOT NULL CHECK(budget_limit>=0),
    status text NOT NULL CHECK(status IN ('draft','ready','archived')),notes text,
    planning_mode text NOT NULL CHECK(planning_mode IN ('shelves','products')),
    product_briefs jsonb NOT NULL CHECK(jsonb_typeof(product_briefs)='array'),
    shelf_selection jsonb CHECK(shelf_selection IS NULL OR jsonb_typeof(shelf_selection)='array'),
    extra_activities jsonb NOT NULL CHECK(jsonb_typeof(extra_activities)='array'),request_ids uuid[] NOT NULL DEFAULT '{}',
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),last_request_hash varchar(64),submitted_revision bigint,
    copied_from uuid REFERENCES merch_supplier_budget_plans(id) ON DELETE RESTRICT,copied_from_revision bigint,copy_request_hash varchar(64),
    created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
    CHECK(EXTRACT(DAY FROM start_month)=1 AND EXTRACT(DAY FROM end_month)=1 AND end_month>=start_month AND end_month<start_month+interval '12 months'),
    CHECK((copied_from IS NULL AND copied_from_revision IS NULL AND copy_request_hash IS NULL) OR
        (copied_from IS NOT NULL AND copied_from_revision IS NOT NULL AND copy_request_hash IS NOT NULL)),
    CHECK(source_id=id AND ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
        (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz')))
);
CREATE INDEX ix_merch_supplier_plans_owner ON merch_supplier_budget_plans(producer_id,updated_at DESC,id);
CREATE TABLE merch_supplier_budget_plan_items (
    id uuid PRIMARY KEY,plan_id uuid NOT NULL REFERENCES merch_supplier_budget_plans(id) ON DELETE RESTRICT,
    source_system varchar(32) NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),source_origin text NOT NULL,
    source_id uuid NOT NULL,source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    product_name text NOT NULL,sku text,activity_type text NOT NULL CHECK(activity_type IN ('placement','promotion','training','motivation')),
    active_months date[] NOT NULL,pharmacy_count integer NOT NULL CHECK(pharmacy_count BETWEEN 1 AND 10000),
    facing_count integer NOT NULL CHECK(facing_count BETWEEN 1 AND 100),people_count integer NOT NULL CHECK(people_count BETWEEN 0 AND 100000),
    unit_price bigint NOT NULL CHECK(unit_price>=0),supplier_category text,note text,position_index integer NOT NULL,
    retired_at timestamptz,created_at timestamptz NOT NULL,
    CHECK(cardinality(active_months) BETWEEN 1 AND 12),
    CHECK(source_id=id AND ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
        (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz')))
);
CREATE TABLE merch_placement_campaigns (
    id uuid PRIMARY KEY,source_system varchar(32) NOT NULL CHECK(source_system IN ('EPHARM','MERCH_CRM')),
    source_origin text NOT NULL,source_id uuid NOT NULL,provenance jsonb NOT NULL CHECK(jsonb_typeof(provenance)='object'),
    CHECK(source_id=id AND ((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz') OR
        (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz')))
);
-- Placement campaigns are distinct from recommendation/promotional campaigns and from the supplier budget plan.
ALTER TABLE merch_media_plans ADD COLUMN source_campaign_id uuid,
    ADD COLUMN campaign_id uuid REFERENCES merch_placement_campaigns(id) ON DELETE RESTRICT,
    ADD COLUMN commercial_plan_id uuid REFERENCES merch_supplier_budget_plans(id) ON DELETE RESTRICT;
CREATE INDEX ix_merch_plan_commercial ON merch_media_plans(commercial_plan_id);
CREATE TABLE merch_commercial_audit (
    id uuid PRIMARY KEY,resource_type text NOT NULL,resource_id uuid NOT NULL,producer_id uuid NOT NULL REFERENCES merch_producers(id),
    revision bigint NOT NULL,action text NOT NULL,actor_type text NOT NULL CHECK(actor_type IN ('ADMIN','PRODUCER')),
    admin_account_id uuid REFERENCES admin_users(id),occurred_at timestamptz NOT NULL,details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'),
    UNIQUE(resource_type,resource_id,revision)
);
CREATE FUNCTION preserve_merch_commercial_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Commercial history is append-only' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_merch_commercial_audit BEFORE UPDATE OR DELETE ON merch_commercial_audit
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_commercial_audit();
