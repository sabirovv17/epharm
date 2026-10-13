-- Persist the identity/last-known-good payload of website products selected in ePharm.
-- This is separate from the complete Medusa snapshot and never changes its generation.
CREATE TABLE storefront_product_references (
    origin TEXT NOT NULL,
    product_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (origin, product_id)
);
