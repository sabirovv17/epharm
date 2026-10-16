-- ACC classification is an explicitly imported, immutable snapshot. A new generation is
-- published by switching one pointer only after its rows have been validated and loaded.
-- Duplicate barcodes are deliberately retained: the matcher fails closed for any barcode
-- belonging to more than one WARE_ID instead of guessing its classification.
CREATE TABLE acc_catalog_snapshots (
    id            UUID PRIMARY KEY,
    sha256        VARCHAR(64) NOT NULL UNIQUE,
    source_name   VARCHAR(255) NOT NULL,
    item_count    INTEGER NOT NULL CHECK (item_count >= 0),
    barcode_count INTEGER NOT NULL CHECK (barcode_count >= 0),
    imported_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE acc_catalog_state (
    singleton          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton = 1),
    active_snapshot_id UUID REFERENCES acc_catalog_snapshots (id)
);
INSERT INTO acc_catalog_state (singleton) VALUES (1);

CREATE TABLE acc_catalog_barcodes (
    snapshot_id    UUID NOT NULL REFERENCES acc_catalog_snapshots (id) ON DELETE CASCADE,
    ware_id        VARCHAR(64) NOT NULL,
    barcode        VARCHAR(32) NOT NULL,
    group_key      VARCHAR(40),
    group_label    VARCHAR(255),
    subgroup_key   VARCHAR(40),
    subgroup_label VARCHAR(255),
    mnn_key        VARCHAR(40),
    mnn_label      VARCHAR(255),
    PRIMARY KEY (snapshot_id, ware_id, barcode),
    CONSTRAINT ck_acc_subgroup_has_group CHECK (subgroup_key IS NULL OR group_key IS NOT NULL)
);

CREATE INDEX ix_acc_catalog_barcode ON acc_catalog_barcodes (snapshot_id, barcode);
CREATE INDEX ix_acc_catalog_group ON acc_catalog_barcodes (snapshot_id, group_key);
CREATE INDEX ix_acc_catalog_subgroup ON acc_catalog_barcodes (snapshot_id, subgroup_key);
CREATE INDEX ix_acc_catalog_mnn ON acc_catalog_barcodes (snapshot_id, mnn_key);

-- Group-scope triggers may refer to ACC items not present in the smaller local products table.
-- Keep the actual scanned product name with the event so the admin journal remains intelligible.
ALTER TABLE recommendation_events ADD COLUMN trigger_name VARCHAR(255);
