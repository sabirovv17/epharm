-- Durable read model of the external Medusa catalogue.
--
-- Medusa's `q` filter performs a slow ILIKE on the remote database and the legacy
-- origin is not sufficiently reliable to sit on the synchronous admin/mobile path.
-- Keep the raw list payload locally, search it in PostgreSQL, and only replace a
-- generation after a complete successful crawl.  A failed refresh therefore never
-- removes the last known-good catalogue.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE medusa_catalog_products (
    sync_id         UUID        NOT NULL,
    id              VARCHAR(64) NOT NULL,
    payload         JSONB       NOT NULL,
    search_text     TEXT        NOT NULL,
    category_ids    TEXT        NOT NULL DEFAULT '',
    source_position INT         NOT NULL,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sync_id, id)
);

CREATE INDEX ix_medusa_catalog_products_position
    ON medusa_catalog_products (sync_id, source_position, id);
CREATE INDEX ix_medusa_catalog_products_search
    ON medusa_catalog_products USING GIN (search_text gin_trgm_ops);

CREATE TABLE medusa_catalog_sync_state (
    singleton       SMALLINT PRIMARY KEY DEFAULT 1,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    active_sync_id  UUID,
    product_count   INT NOT NULL DEFAULT 0 CHECK (product_count >= 0),
    last_error      TEXT,
    CONSTRAINT ck_medusa_catalog_sync_singleton CHECK (singleton = 1)
);

INSERT INTO medusa_catalog_sync_state(singleton) VALUES (1);
