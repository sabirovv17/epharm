-- Independent staff identity: never blocks Windows or the fiscal cash register.
CREATE TABLE posm_staff_challenges (
    id UUID PRIMARY KEY,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('register','reset','activate')),
    device_id VARCHAR(128) NOT NULL,
    pharmacy_id VARCHAR(64) NOT NULL,
    iin VARCHAR(12) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    pharmacist_id VARCHAR(64),
    eligible BOOLEAN NOT NULL,
    provider VARCHAR(32) NOT NULL,
    code_hash VARCHAR(64),
    attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_posm_staff_challenge_phone ON posm_staff_challenges(phone,created_at);
CREATE INDEX ix_posm_staff_challenge_expiry ON posm_staff_challenges(expires_at);
CREATE TABLE posm_staff_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    pharmacy_id VARCHAR(64) NOT NULL,
    password_fingerprint VARCHAR(64) NOT NULL,
    standard_n_user_id VARCHAR(128) NOT NULL,
    standard_n_session_id VARCHAR(128) NOT NULL,
    workstation_id VARCHAR(128) NOT NULL,
    process_instance_id VARCHAR(256) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_posm_staff_sessions_pharmacist ON posm_staff_sessions(pharmacist_id);
CREATE INDEX ix_posm_staff_sessions_device ON posm_staff_sessions(device_id,pharmacy_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_posm_staff_sessions_expiry ON posm_staff_sessions(expires_at);
CREATE TABLE posm_staff_rate_limits (
    scope_hash VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL
);

-- Revocation must survive a later unblock/reassignment, including writers from older backends.
CREATE FUNCTION posm_staff_revoke_pharmacist_sessions() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status
       OR OLD.pharmacy_id IS DISTINCT FROM NEW.pharmacy_id
       OR OLD.password_hash IS DISTINCT FROM NEW.password_hash THEN
        UPDATE posm_staff_sessions SET revoked_at=now()
        WHERE pharmacist_id=NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_posm_staff_pharmacist_revoke
AFTER UPDATE OF status,pharmacy_id,password_hash ON pharmacists
FOR EACH ROW EXECUTE FUNCTION posm_staff_revoke_pharmacist_sessions();

CREATE FUNCTION posm_staff_revoke_device_sessions() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.active IS DISTINCT FROM NEW.active
       OR OLD.secret_sha256 IS DISTINCT FROM NEW.secret_sha256
       OR OLD.pharmacy_id IS DISTINCT FROM NEW.pharmacy_id THEN
        UPDATE posm_staff_sessions SET revoked_at=now()
        WHERE device_id=OLD.device_id AND pharmacy_id=OLD.pharmacy_id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_posm_staff_device_revoke
AFTER UPDATE OF active,secret_sha256,pharmacy_id ON fulfillment_devices
FOR EACH ROW EXECUTE FUNCTION posm_staff_revoke_device_sessions();
