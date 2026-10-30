-- Source placement_item_client_photos contains zero rows in the verified source snapshot.
-- New versions are private and retained; an archive or replacement never deletes bytes/history.
CREATE TABLE merch_placement_reference_photos (
    id uuid PRIMARY KEY,
    media_plan_item_id uuid NOT NULL REFERENCES merch_media_plan_items(id) ON DELETE RESTRICT,
    storage_backend text NOT NULL CHECK (storage_backend='private_host'),
    object_key text NOT NULL UNIQUE CHECK (object_key ~ '^placement-reference/[0-9a-f-]{36}/[0-9a-f-]{36}$'),
    mime_type text NOT NULL CHECK (mime_type IN ('image/jpeg','image/png')),
    byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 8388608),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    file_name varchar(240) NOT NULL,
    created_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    created_by_producer uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    archived_by_admin uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    archived_by_producer uuid REFERENCES merch_producers(id) ON DELETE RESTRICT,
    CHECK ((created_by_admin IS NOT NULL)::int+(created_by_producer IS NOT NULL)::int=1),
    CHECK ((archived_at IS NULL AND archived_by_admin IS NULL AND archived_by_producer IS NULL) OR
           (archived_at IS NOT NULL AND (archived_by_admin IS NOT NULL)::int+(archived_by_producer IS NOT NULL)::int=1))
);
CREATE UNIQUE INDEX ux_merch_reference_active_item ON merch_placement_reference_photos(media_plan_item_id) WHERE archived_at IS NULL;
CREATE INDEX ix_merch_reference_history ON merch_placement_reference_photos(media_plan_item_id,created_at,id);
CREATE FUNCTION protect_merch_placement_reference_photo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'reference photo history is immutable'; END IF;
    IF OLD.archived_at IS NOT NULL OR NEW.archived_at IS NULL OR
       (to_jsonb(OLD)-ARRAY['archived_at','archived_by_admin','archived_by_producer']) IS DISTINCT FROM
       (to_jsonb(NEW)-ARRAY['archived_at','archived_by_admin','archived_by_producer']) THEN
        RAISE EXCEPTION 'only initial reference photo archive is allowed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_merch_reference_immutable BEFORE UPDATE OR DELETE ON merch_placement_reference_photos
FOR EACH ROW EXECUTE FUNCTION protect_merch_placement_reference_photo();
