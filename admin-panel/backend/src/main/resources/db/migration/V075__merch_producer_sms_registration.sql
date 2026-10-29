-- A new customer exists only after a successfully consumed, purpose-bound SMS challenge.
ALTER TABLE merch_producer_challenges ADD COLUMN registration_pending boolean NOT NULL DEFAULT false;
ALTER TABLE merch_producer_challenges DROP CONSTRAINT merch_producer_challenges_check;
ALTER TABLE merch_producer_challenges ADD CONSTRAINT ck_merch_producer_challenge_identity CHECK (
    (NOT registration_pending OR (eligible AND customer_id IS NULL)) AND
    (NOT eligible OR customer_id IS NOT NULL OR registration_pending)
);
