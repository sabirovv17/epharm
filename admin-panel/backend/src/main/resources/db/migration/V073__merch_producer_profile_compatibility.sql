-- Legacy advertiser_profiles.company_name is nullable; preserve absence instead of inventing a company.
ALTER TABLE merch_producers ALTER COLUMN name DROP NOT NULL;
ALTER TABLE merch_producers ADD COLUMN profile_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(profile_json)='object');
ALTER TABLE merch_producers ADD COLUMN profile_revision bigint NOT NULL DEFAULT 0 CHECK(profile_revision>=0);
ALTER TABLE merch_media_plans ALTER COLUMN advertiser_name DROP NOT NULL;

CREATE TABLE merch_producer_profile_audit (
    id uuid PRIMARY KEY,
    producer_id uuid NOT NULL REFERENCES merch_producers(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL REFERENCES merch_producer_sessions(id) ON DELETE RESTRICT,
    revision bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    before_json jsonb NOT NULL,
    after_json jsonb NOT NULL,
    UNIQUE(producer_id,revision)
);
CREATE FUNCTION reject_producer_profile_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Producer profile audit is append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER merch_producer_profile_audit_append_only BEFORE UPDATE OR DELETE ON merch_producer_profile_audit
    FOR EACH ROW EXECUTE FUNCTION reject_producer_profile_audit_mutation();
