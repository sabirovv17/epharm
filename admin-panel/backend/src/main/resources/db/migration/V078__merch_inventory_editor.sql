ALTER TABLE merch_organizations ADD COLUMN editor_revision bigint NOT NULL DEFAULT 1 CHECK(editor_revision>0);
ALTER TABLE merch_branches ADD COLUMN editor_revision bigint NOT NULL DEFAULT 1 CHECK(editor_revision>0),
    ADD COLUMN floor_plan jsonb, ADD COLUMN floor_plan_draft jsonb, ADD COLUMN floor_plan_published_at timestamptz,
    ADD COLUMN traffic jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(traffic)='object');
-- Preserve the exact imported plans; normalization is a read projection, not a source rewrite.
UPDATE merch_branches SET floor_plan=source_payload->'floor_plan',floor_plan_draft=source_payload->'floor_plan_draft'
WHERE source_system='MERCH_CRM';
UPDATE merch_branches SET traffic=jsonb_build_object('otsPerDay',source_payload->'traffic_ots_per_day',
    'vehicleTrafficPerDay',source_payload->'vehicle_traffic_per_day','pedestrianTrafficPerDay',source_payload->'pedestrian_traffic_per_day',
    'source',source_payload->'traffic_source','measuredAt',source_payload->'traffic_measured_at') WHERE source_system='MERCH_CRM';
CREATE TABLE merch_inventory_media (
    id uuid PRIMARY KEY, branch_id uuid NOT NULL REFERENCES merch_branches(id),
    shelf_id uuid REFERENCES merch_shelves(id), angle varchar(16) NOT NULL CHECK(angle IN('facade','entrance','hall','checkout','panorama','shelf')),
    caption text NOT NULL CHECK(char_length(caption)<=300), storage_backend varchar(32) NOT NULL,
    object_key text NOT NULL UNIQUE, mime_type varchar(32) NOT NULL CHECK(mime_type IN('image/jpeg','image/png')),
    byte_size integer NOT NULL CHECK(byte_size>0), sha256 varchar(64) NOT NULL,
    archived_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), created_by uuid REFERENCES admin_users(id),
    source_system varchar(32) NOT NULL DEFAULT 'EPHARM',source_origin text NOT NULL DEFAULT 'https://epharm.inkar.kz',
    source_id uuid,source_uploader_id uuid,source_payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(source_payload)='object'),
    CHECK((angle='shelf')=(shelf_id IS NOT NULL)),
    CHECK((source_system='EPHARM' AND source_origin='https://epharm.inkar.kz' AND created_by IS NOT NULL AND source_uploader_id IS NULL AND storage_backend='private_host') OR
          (source_system='MERCH_CRM' AND source_origin='https://crm.inkar.kz' AND source_id=id AND created_by IS NULL AND storage_backend='legacy_reference'))
);
CREATE INDEX merch_inventory_media_branch ON merch_inventory_media(branch_id,created_at);
CREATE TABLE merch_inventory_editor_audit (
    id uuid PRIMARY KEY, branch_id uuid REFERENCES merch_branches(id), organization_id uuid REFERENCES merch_organizations(id),
    actor_id uuid NOT NULL REFERENCES admin_users(id), operation varchar(48) NOT NULL,
    revision bigint NOT NULL, details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER merch_editor_audit_append_only BEFORE UPDATE OR DELETE ON merch_inventory_editor_audit
FOR EACH ROW EXECUTE FUNCTION reject_merch_inventory_audit_mutation();
CREATE TABLE merch_floor_plan_versions (
    id uuid PRIMARY KEY,branch_id uuid NOT NULL REFERENCES merch_branches(id), revision bigint NOT NULL,
    plan jsonb NOT NULL CHECK(jsonb_typeof(plan)='object'), published_by uuid NOT NULL REFERENCES admin_users(id), published_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(branch_id,revision)
);
CREATE TRIGGER merch_floor_plan_append_only BEFORE UPDATE OR DELETE ON merch_floor_plan_versions
FOR EACH ROW EXECUTE FUNCTION reject_merch_inventory_audit_mutation();
