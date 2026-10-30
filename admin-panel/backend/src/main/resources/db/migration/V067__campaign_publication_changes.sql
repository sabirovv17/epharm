-- Published rule IDs remain available to historical events/reports after replacement.
ALTER TABLE rules ADD COLUMN retired_at timestamptz;
ALTER TABLE rules ADD CONSTRAINT ck_rules_retired_archived
    CHECK (retired_at IS NULL OR status = 'archived');

CREATE TABLE promo_publication_changes (
    promo_id varchar(64) PRIMARY KEY REFERENCES promos(id) ON DELETE RESTRICT,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    base_fingerprint varchar(64),
    bundle_json text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64) NOT NULL,
    CHECK ((bundle_json IS NULL) = (base_fingerprint IS NULL))
);

CREATE TABLE promo_publication_history (
    promo_id varchar(64) NOT NULL REFERENCES promos(id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK (revision > 0),
    applied_at timestamptz NOT NULL DEFAULT now(),
    applied_by varchar(64) NOT NULL,
    bundle_json text NOT NULL,
    previous_fingerprint varchar(64) NOT NULL,
    published_fingerprint varchar(64) NOT NULL,
    PRIMARY KEY (promo_id, revision)
);

CREATE FUNCTION reject_promo_publication_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Campaign publication history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER promo_publication_history_append_only BEFORE UPDATE OR DELETE ON promo_publication_history
FOR EACH ROW EXECUTE FUNCTION reject_promo_publication_history_mutation();
