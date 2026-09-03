-- Reliable storefront -> ePharm -> POSM order fulfilment.
-- Store/customer PII is deliberately absent: only pharmacy, payment state and line snapshots cross this boundary.

CREATE TABLE fulfillment_pharmacy_links (
    external_id      VARCHAR(256) PRIMARY KEY,
    pharmacy_id      VARCHAR(64) NOT NULL UNIQUE REFERENCES pharmacies(id) ON DELETE RESTRICT,
    crm_branch_id    VARCHAR(256),
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_fulfillment_pharmacy_links_crm
    ON fulfillment_pharmacy_links (crm_branch_id) WHERE crm_branch_id IS NOT NULL;

CREATE TABLE fulfillment_orders (
    order_id             VARCHAR(256) PRIMARY KEY,
    event_id             UUID NOT NULL UNIQUE,
    order_number         VARCHAR(128) NOT NULL,
    request_sha256       CHAR(64) NOT NULL,
    pharmacy_external_id VARCHAR(256) NOT NULL,
    pharmacy_id          VARCHAR(64) REFERENCES pharmacies(id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ NOT NULL,
    total                NUMERIC(14,2) NOT NULL,
    currency             CHAR(3) NOT NULL,
    delivery             VARCHAR(32) NOT NULL,
    payment_method       VARCHAR(32) NOT NULL,
    payment_status       VARCHAR(32) NOT NULL,
    is_demo              BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_code_hmac     CHAR(64) NOT NULL,
    pickup_attempts      INT NOT NULL DEFAULT 0,
    pickup_locked_until  TIMESTAMPTZ,
    status               VARCHAR(24) NOT NULL DEFAULT 'submitted',
    version              BIGINT NOT NULL DEFAULT 1,
    cancellation_reason  VARCHAR(500),
    completed_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_fulfillment_order_total CHECK (total >= 0),
    CONSTRAINT ck_fulfillment_order_currency CHECK (currency = 'KZT'),
    CONSTRAINT ck_fulfillment_order_status CHECK (status IN (
        'submitted', 'assembling', 'ready', 'completed', 'cancelled'
    )),
    CONSTRAINT ck_fulfillment_order_attempts CHECK (pickup_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_fulfillment_order_version CHECK (version > 0),
    CONSTRAINT ck_fulfillment_order_terminal_time CHECK (
        (status = 'completed' AND completed_at IS NOT NULL)
        OR (status <> 'completed')
    )
);

CREATE INDEX ix_fulfillment_orders_pharmacy_status
    ON fulfillment_orders (pharmacy_id, status, created_at, order_id)
    WHERE pharmacy_id IS NOT NULL;
CREATE INDEX ix_fulfillment_orders_unassigned
    ON fulfillment_orders (created_at, order_id) WHERE pharmacy_id IS NULL;
CREATE INDEX ix_fulfillment_orders_external_pharmacy
    ON fulfillment_orders (pharmacy_external_id, status, created_at);

CREATE TABLE fulfillment_order_lines (
    order_id    VARCHAR(256) NOT NULL REFERENCES fulfillment_orders(order_id) ON DELETE CASCADE,
    line_no     INT NOT NULL,
    product_id  VARCHAR(256) NOT NULL,
    sku         VARCHAR(256) NOT NULL DEFAULT '',
    title       VARCHAR(500) NOT NULL,
    quantity    INT NOT NULL,
    unit_price  NUMERIC(14,2),
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT ck_fulfillment_line_no CHECK (line_no BETWEEN 0 AND 199),
    CONSTRAINT ck_fulfillment_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_fulfillment_line_price CHECK (unit_price >= 0)
);

CREATE INDEX ix_fulfillment_order_lines_product
    ON fulfillment_order_lines (product_id, order_id);
CREATE INDEX ix_fulfillment_order_lines_sku
    ON fulfillment_order_lines (sku, order_id) WHERE sku <> '';

-- This sequence is the only synchronization cursor. Consumers persist the last applied value,
-- never timestamps, so equal clocks and retries cannot reorder status changes.
CREATE TABLE fulfillment_order_updates (
    cursor       BIGSERIAL PRIMARY KEY,
    order_id     VARCHAR(256) NOT NULL REFERENCES fulfillment_orders(order_id) ON DELETE CASCADE,
    version      BIGINT NOT NULL,
    status       VARCHAR(24) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    source       VARCHAR(24) NOT NULL,
    UNIQUE (order_id, version),
    CONSTRAINT ck_fulfillment_update_source CHECK (source IN ('posm', 'hq', 'system'))
);

CREATE INDEX ix_fulfillment_order_updates_cursor
    ON fulfillment_order_updates (cursor);

CREATE TABLE fulfillment_devices (
    id             UUID PRIMARY KEY,
    device_id      VARCHAR(128) NOT NULL,
    pharmacy_id    VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    secret_sha256  CHAR(64) NOT NULL UNIQUE,
    secret_hint    VARCHAR(16) NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at     TIMESTAMPTZ,
    UNIQUE (device_id, pharmacy_id)
);

CREATE INDEX ix_fulfillment_devices_pharmacy
    ON fulfillment_devices (pharmacy_id, active, last_seen_at DESC);

CREATE TABLE fulfillment_audit (
    id          BIGSERIAL PRIMARY KEY,
    order_id    VARCHAR(256) REFERENCES fulfillment_orders(order_id) ON DELETE SET NULL,
    actor_type  VARCHAR(24) NOT NULL,
    actor_id    VARCHAR(256) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    details     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_fulfillment_audit_actor CHECK (actor_type IN ('storefront', 'posm', 'hq', 'system'))
);

CREATE INDEX ix_fulfillment_audit_order_time
    ON fulfillment_audit (order_id, created_at DESC);
