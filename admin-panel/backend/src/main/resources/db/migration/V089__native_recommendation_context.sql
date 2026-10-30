-- Owned exact-key recommendation/stock feed. Task observations are not authoritative stock.
CREATE TABLE merch_recommendation_signals (
    id uuid PRIMARY KEY,
    source_system text NOT NULL CHECK (source_system IN ('MERCH_CRM','EPHARM')),
    source_origin text NOT NULL,
    source_id uuid NOT NULL CHECK (source_id=id),
    source_payload jsonb NOT NULL CHECK (jsonb_typeof(source_payload)='object'),
    organization_id uuid NOT NULL REFERENCES merch_organizations(id) ON DELETE RESTRICT,
    branch_id uuid REFERENCES merch_branches(id) ON DELETE RESTRICT,
    kind text NOT NULL CHECK (kind IN ('substitution','crosssell')),
    trigger_barcode varchar(32), trigger_ipart_id varchar(64),
    recommend_product_id varchar(64), recommend_barcode varchar(32), recommend_ipart_id varchar(64),
    script text NOT NULL DEFAULT '', advantages jsonb NOT NULL DEFAULT '[]'::jsonb,
    partner_label varchar(80), bonus integer NOT NULL DEFAULT 0 CHECK (bonus BETWEEN 0 AND 1000000),
    priority integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN -1000 AND 1000),
    active boolean NOT NULL DEFAULT true, valid_from timestamptz, valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NULLIF(btrim(trigger_barcode),'') IS NOT NULL OR NULLIF(btrim(trigger_ipart_id),'') IS NOT NULL),
    CHECK (NULLIF(btrim(recommend_product_id),'') IS NOT NULL OR NULLIF(btrim(recommend_barcode),'') IS NOT NULL OR NULLIF(btrim(recommend_ipart_id),'') IS NOT NULL),
    CHECK (jsonb_typeof(advantages)='array'),
    CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until>valid_from),
    CHECK ((source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz') OR (source_system='EPHARM' AND source_origin='https://epharm.inkar.kz'))
);
CREATE INDEX merch_context_signals_scope_idx ON merch_recommendation_signals(organization_id,branch_id,priority DESC,id) WHERE active;
CREATE INDEX merch_context_signals_barcode_idx ON merch_recommendation_signals(trigger_barcode) WHERE active AND trigger_barcode IS NOT NULL;
CREATE INDEX merch_context_signals_ipart_idx ON merch_recommendation_signals(trigger_ipart_id) WHERE active AND trigger_ipart_id IS NOT NULL;

CREATE TABLE merch_product_balances (
    id bigint PRIMARY KEY CHECK (id>0),
    source_system text NOT NULL CHECK (source_system IN ('MERCH_CRM','EPHARM')),
    source_origin text NOT NULL,
    source_id bigint NOT NULL CHECK (source_id=id),
    source_payload jsonb NOT NULL CHECK (jsonb_typeof(source_payload)='object'),
    organization_id uuid NOT NULL REFERENCES merch_organizations(id) ON DELETE RESTRICT,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    external_key varchar(160) NOT NULL,
    product_id varchar(64), barcode varchar(32), ipart_id varchar(64),
    pharmacy_available_qty numeric(14,3), network_available_qty numeric(14,3),
    warehouse_available_qty numeric(14,3), reserved_qty numeric(14,3),
    quality text NOT NULL DEFAULT 'confirmed' CHECK (quality IN ('confirmed','estimated')),
    source varchar(80) NOT NULL, observed_at timestamptz NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NULLIF(btrim(product_id),'') IS NOT NULL OR NULLIF(btrim(barcode),'') IS NOT NULL OR NULLIF(btrim(ipart_id),'') IS NOT NULL),
    CHECK ((pharmacy_available_qty IS NULL OR pharmacy_available_qty>=0) AND (network_available_qty IS NULL OR network_available_qty>=0) AND (warehouse_available_qty IS NULL OR warehouse_available_qty>=0) AND (reserved_qty IS NULL OR reserved_qty>=0)),
    CHECK ((source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz') OR (source_system='EPHARM' AND source_origin='https://epharm.inkar.kz')),
    UNIQUE(branch_id,external_key)
);
CREATE INDEX merch_context_balance_product_idx ON merch_product_balances(branch_id,product_id) WHERE product_id IS NOT NULL;
CREATE INDEX merch_context_balance_barcode_idx ON merch_product_balances(branch_id,barcode) WHERE barcode IS NOT NULL;
CREATE INDEX merch_context_balance_ipart_idx ON merch_product_balances(branch_id,ipart_id) WHERE ipart_id IS NOT NULL;
REVOKE ALL ON merch_recommendation_signals,merch_product_balances FROM PUBLIC;
