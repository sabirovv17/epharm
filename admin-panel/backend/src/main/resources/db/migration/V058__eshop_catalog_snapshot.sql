-- Independent, complete generations from the private shop exporter. The active
-- pointer changes only after the NDJSON footer, checksum and unique count agree.
CREATE TABLE eshop_catalog_sync_state (
    singleton SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton = 1),
    active_generation UUID,
    catalog_run_id TEXT,
    availability_run_id TEXT,
    product_count INT NOT NULL DEFAULT 0 CHECK (product_count >= 0),
    published_count INT NOT NULL DEFAULT 0 CHECK (published_count >= 0),
    catalog_generated_at TIMESTAMPTZ,
    availability_finished_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error TEXT
);
INSERT INTO eshop_catalog_sync_state (singleton) VALUES (1);

CREATE TABLE eshop_catalog_products (
    generation UUID NOT NULL,
    sku VARCHAR(128) NOT NULL,
    public_id VARCHAR(64) NOT NULL,
    published BOOLEAN NOT NULL,
    price_amount NUMERIC(18,2),
    source_payload JSONB NOT NULL,
    product_payload JSONB NOT NULL,
    search_text TEXT NOT NULL,
    category_keys TEXT[] NOT NULL DEFAULT '{}',
    source_position INT NOT NULL,
    PRIMARY KEY (generation, sku),
    UNIQUE (generation, public_id)
);
CREATE INDEX ix_eshop_catalog_products_position
    ON eshop_catalog_products (generation, published, source_position, sku);
CREATE INDEX ix_eshop_catalog_products_admin_position
    ON eshop_catalog_products (generation, source_position, sku);
CREATE INDEX ix_eshop_catalog_products_search
    ON eshop_catalog_products USING GIN (search_text gin_trgm_ops);
CREATE INDEX ix_eshop_catalog_products_categories
    ON eshop_catalog_products USING GIN (category_keys);

CREATE TABLE eshop_catalog_aliases (
    generation UUID NOT NULL,
    alias_id VARCHAR(64) NOT NULL,
    sku VARCHAR(128) NOT NULL,
    PRIMARY KEY (generation, alias_id),
    FOREIGN KEY (generation, sku) REFERENCES eshop_catalog_products (generation, sku)
        ON DELETE CASCADE
);
CREATE INDEX ix_eshop_catalog_aliases_sku
    ON eshop_catalog_aliases (generation, sku);
