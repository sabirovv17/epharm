-- Owned inventory; the temporary CRM staging schema is never an operational read model.
-- Source UUIDs stay opaque and unchanged. Source accounts are not ePharm admin identities.
CREATE TABLE merch_organizations (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    name text NOT NULL,
    legal_name text NOT NULL,
    bin varchar(12),
    active boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    UNIQUE (source_system, source_origin, source_id)
);

CREATE TABLE merch_branches (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    organization_id uuid NOT NULL REFERENCES merch_organizations(id) ON DELETE RESTRICT,
    epharm_pharmacy_id varchar(64) REFERENCES pharmacies(id) ON DELETE RESTRICT,
    pharmacy_binding_verified boolean NOT NULL DEFAULT false,
    code text NOT NULL,
    name text NOT NULL,
    address text NOT NULL,
    city text NOT NULL,
    division text,
    latitude numeric(9,6),
    longitude numeric(9,6),
    price_adjustment_percent numeric(6,2) NOT NULL DEFAULT 0 CHECK (price_adjustment_percent BETWEEN -50 AND 100),
    active boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    CHECK ((epharm_pharmacy_id IS NULL AND NOT pharmacy_binding_verified) OR
           (epharm_pharmacy_id IS NOT NULL AND pharmacy_binding_verified)),
    UNIQUE (source_system, source_origin, source_id),
    UNIQUE (organization_id, code)
);

CREATE TABLE merch_inventory_containers (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    container_type text NOT NULL CHECK (container_type IN ('cabinet', 'zone', 'wall', 'stand')),
    code text NOT NULL,
    name text NOT NULL,
    category text,
    position_index integer NOT NULL CHECK (position_index BETWEEN 0 AND 999),
    active boolean NOT NULL,
    metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    UNIQUE (source_system, source_origin, source_id),
    UNIQUE (branch_id, code),
    UNIQUE (id, branch_id)
);

CREATE TABLE merch_shelves (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    container_id uuid,
    code text NOT NULL,
    category text NOT NULL,
    width_m numeric(5,2) NOT NULL CHECK (width_m BETWEEN 0.25 AND 20.00),
    base_monthly_price integer NOT NULL CHECK (base_monthly_price >= 0),
    active boolean NOT NULL,
    place_type varchar(16) NOT NULL CHECK (place_type IN ('shelf', 'screen', 'tv')),
    title text,
    position_index integer NOT NULL CHECK (position_index BETWEEN 0 AND 999),
    specifications jsonb NOT NULL CHECK (jsonb_typeof(specifications) = 'object'),
    sale_mode varchar(32) NOT NULL CHECK (sale_mode IN ('whole_only', 'whole_or_halves')),
    half_monthly_price integer CHECK (half_monthly_price IS NULL OR half_monthly_price >= 5000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    FOREIGN KEY (container_id, branch_id) REFERENCES merch_inventory_containers(id, branch_id) ON DELETE RESTRICT,
    UNIQUE (source_system, source_origin, source_id),
    UNIQUE (branch_id, code)
);

CREATE TABLE merch_monthly_inventory (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    shelf_id uuid NOT NULL REFERENCES merch_shelves(id) ON DELETE RESTRICT,
    placement_month date NOT NULL CHECK (EXTRACT(DAY FROM placement_month) = 1),
    status varchar(16) NOT NULL CHECK (status IN ('available', 'held', 'booked', 'blocked')),
    monthly_price integer NOT NULL CHECK (monthly_price >= 0),
    held_until timestamptz,
    block_reason varchar(16) CHECK (block_reason IN ('repair', 'own_promo', 'reserve')),
    block_note text CHECK (char_length(block_note) <= 300),
    blocked_by_source uuid,
    blocked_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    blocked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    UNIQUE (source_system, source_origin, source_id),
    UNIQUE (shelf_id, placement_month)
);

-- Booking authoring and expiry are later transitions. These typed facts already protect the calendar.
CREATE TABLE merch_media_plans (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    advertiser_name text NOT NULL,
    placement_month date NOT NULL CHECK (EXTRACT(DAY FROM placement_month) = 1),
    status varchar(16) NOT NULL CHECK (status IN ('draft', 'pending', 'approved', 'rejected', 'cancelled')),
    held_until timestamptz,
    origin varchar(32) NOT NULL CHECK (origin IN ('request', 'admin_assignment')),
    total_amount integer NOT NULL CHECK (total_amount >= 0),
    submitted_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    UNIQUE (source_system, source_origin, source_id)
);

CREATE TABLE merch_media_plan_items (
    id uuid PRIMARY KEY,
    source_system varchar(32) NOT NULL,
    source_origin text NOT NULL,
    source_id uuid NOT NULL,
    source_payload jsonb NOT NULL,
    media_plan_id uuid NOT NULL REFERENCES merch_media_plans(id) ON DELETE RESTRICT,
    shelf_id uuid NOT NULL REFERENCES merch_shelves(id) ON DELETE RESTRICT,
    placement_month date NOT NULL CHECK (EXTRACT(DAY FROM placement_month) = 1),
    monthly_price integer NOT NULL CHECK (monthly_price >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('draft', 'pending', 'approved', 'rejected', 'cancelled')),
    sale_portion varchar(16) CHECK (sale_portion IN ('whole', 'left', 'right')),
    created_at timestamptz NOT NULL,
    CHECK (source_system IN ('MERCH_CRM', 'EPHARM')),
    CHECK (source_origin IN ('https://crm.inkar.kz', 'https://epharm.inkar.kz')),
    CHECK (jsonb_typeof(source_payload) = 'object'),
    CHECK (source_id = id AND ((source_system = 'MERCH_CRM' AND source_origin = 'https://crm.inkar.kz') OR
        (source_system = 'EPHARM' AND source_origin = 'https://epharm.inkar.kz'))),
    UNIQUE (source_system, source_origin, source_id),
    UNIQUE (media_plan_id, shelf_id)
);

CREATE INDEX merch_branch_org_idx ON merch_branches(organization_id, city, id);
CREATE INDEX merch_shelf_branch_idx ON merch_shelves(branch_id, category, code);
CREATE INDEX merch_inventory_month_idx ON merch_monthly_inventory(shelf_id, placement_month);
CREATE INDEX merch_placement_month_idx ON merch_media_plan_items(shelf_id, placement_month, status);

CREATE TABLE merch_inventory_audit (
    id uuid PRIMARY KEY,
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    actor_account_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    action varchar(16) NOT NULL CHECK (action IN ('block', 'unblock')),
    occurred_at timestamptz NOT NULL,
    details jsonb NOT NULL CHECK (jsonb_typeof(details) = 'object')
);
CREATE FUNCTION reject_merch_inventory_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Merchandising inventory audit is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER merch_inventory_audit_append_only BEFORE UPDATE OR DELETE ON merch_inventory_audit
FOR EACH ROW EXECUTE FUNCTION reject_merch_inventory_audit_mutation();
