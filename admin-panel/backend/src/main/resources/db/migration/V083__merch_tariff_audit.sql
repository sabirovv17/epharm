CREATE TABLE merch_tariff_audit (
    id uuid PRIMARY KEY,
    category text NOT NULL REFERENCES merch_supplier_pricing_tiers(category) ON DELETE RESTRICT,
    actor_admin_id uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    previous_updated_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    before_json jsonb NOT NULL CHECK(jsonb_typeof(before_json)='object'),
    after_json jsonb NOT NULL CHECK(jsonb_typeof(after_json)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK(updated_at>previous_updated_at),
    UNIQUE(category,updated_at)
);
CREATE FUNCTION preserve_merch_tariff_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Tariff history is append-only' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_merch_tariff_audit BEFORE UPDATE OR DELETE ON merch_tariff_audit
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_tariff_audit();
