-- A self-declared registration number is not proof of phone ownership. Existing rows stay unknown.
ALTER TABLE pharmacists ADD COLUMN phone_verification_status VARCHAR(16);
ALTER TABLE pharmacists ADD CONSTRAINT ck_pharmacists_phone_verification_status
    CHECK (phone_verification_status IS NULL OR phone_verification_status IN ('self_declared', 'sms_verified'));
