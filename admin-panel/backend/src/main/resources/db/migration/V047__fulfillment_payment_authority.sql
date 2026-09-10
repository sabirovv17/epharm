-- Keep the browser/storefront claim separate from the payment state trusted by ePharm.
-- Until a server-side payment producer is explicitly allowlisted, non-cash "paid"
-- claims are persisted as pending and cannot be issued by POSM.
ALTER TABLE fulfillment_orders
    ADD COLUMN payment_status_claimed VARCHAR(32) NOT NULL DEFAULT 'pending',
    ADD COLUMN payment_authority VARCHAR(128);

UPDATE fulfillment_orders
SET payment_status_claimed = payment_status;
