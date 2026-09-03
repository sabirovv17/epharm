-- Frozen outbound order bodies and monotonic ePharm status-feed state.
-- Existing pre-release outbox rows remain untouched and are excluded by the worker because
-- they have no frozen_body and predate EPHARM_ORDER_START_AT.

ALTER TABLE site_orders
    ALTER COLUMN total_amount TYPE numeric(14,2)
    USING total_amount::numeric(14,2);

ALTER TABLE integration_outbox
    ADD COLUMN frozen_body text,
    ADD COLUMN frozen_sha256 char(64);

ALTER TABLE integration_outbox
    ADD CONSTRAINT integration_outbox_frozen_pair_chk CHECK (
        (frozen_body IS NULL AND frozen_sha256 IS NULL)
        OR (frozen_body IS NOT NULL AND frozen_sha256 ~ '^[0-9a-f]{64}$')
    );

ALTER TABLE site_orders
    ADD COLUMN fulfillment_status text,
    ADD COLUMN fulfillment_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN fulfillment_cursor bigint NOT NULL DEFAULT 0;

ALTER TABLE site_orders
    ADD CONSTRAINT site_orders_fulfillment_status_chk CHECK (
        fulfillment_status IS NULL
        OR fulfillment_status IN ('submitted', 'assembling', 'ready', 'completed', 'cancelled')
    );

CREATE TABLE epharm_worker_state (
    worker_key text PRIMARY KEY,
    cursor bigint NOT NULL DEFAULT 0 CHECK (cursor >= 0),
    last_outbound_at timestamptz,
    last_inbound_at timestamptz,
    last_error text,
    last_error_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO epharm_worker_state (worker_key, cursor)
VALUES ('order-updates', 0)
ON CONFLICT (worker_key) DO NOTHING;

CREATE TABLE epharm_sync_diagnostics (
    id bigserial PRIMARY KEY,
    direction text NOT NULL CHECK (direction IN ('outbound', 'inbound')),
    aggregate_id text,
    error_code text NOT NULL,
    error_message text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX epharm_sync_diagnostics_time_idx
    ON epharm_sync_diagnostics (occurred_at DESC);
CREATE INDEX site_orders_fulfillment_status_idx
    ON site_orders (fulfillment_status, placed_at DESC)
    WHERE fulfillment_status IS NOT NULL;

CREATE INDEX integration_outbox_epharm_dispatch_idx
    ON integration_outbox (available_at, created_at)
    WHERE topic = 'epharm.order.created'
      AND frozen_body IS NOT NULL
      AND status IN ('pending', 'failed');
