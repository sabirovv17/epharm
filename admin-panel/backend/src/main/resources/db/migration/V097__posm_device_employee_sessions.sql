-- Own employee proof is device/process scoped; no invented Standard-N identity or seller mapping.
CREATE TABLE posm_device_staff_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    pharmacy_id VARCHAR(64) NOT NULL,
    password_fingerprint VARCHAR(64) NOT NULL,
    process_instance_id VARCHAR(256) NOT NULL,
    standard_n_user_id VARCHAR(128),
    standard_n_session_id VARCHAR(128),
    workstation_id VARCHAR(128),
    standard_n_iin VARCHAR(12),
    standard_n_iin_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    standard_n_full_name VARCHAR(255),
    standard_n_full_name_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_posm_device_staff_source_binding CHECK (
        (standard_n_user_id IS NULL AND standard_n_session_id IS NULL AND workstation_id IS NULL
            AND standard_n_iin IS NULL AND standard_n_iin_status='unavailable'
            AND standard_n_full_name IS NULL AND standard_n_full_name_status='unavailable') OR
        (standard_n_user_id IS NOT NULL AND length(btrim(standard_n_user_id))>0
            AND standard_n_session_id IS NOT NULL AND length(btrim(standard_n_session_id))>0
            AND workstation_id IS NOT NULL AND length(btrim(workstation_id))>0)
    ),
    CONSTRAINT ck_posm_device_staff_source_iin CHECK (
        (standard_n_iin_status='available' AND standard_n_iin IS NOT NULL AND standard_n_iin ~ '^[0-9]{12}$') OR
        (standard_n_iin_status IN ('missing','invalid','unavailable','ambiguous') AND standard_n_iin IS NULL)
    ),
    CONSTRAINT ck_posm_device_staff_source_name CHECK (
        (standard_n_full_name_status='available' AND standard_n_full_name IS NOT NULL
            AND length(btrim(standard_n_full_name)) BETWEEN 1 AND 255) OR
        (standard_n_full_name_status IN ('missing','invalid','unavailable','ambiguous') AND standard_n_full_name IS NULL)
    )
);
CREATE INDEX ix_posm_device_staff_person ON posm_device_staff_sessions(pharmacist_id);
CREATE INDEX ix_posm_device_staff_device ON posm_device_staff_sessions(device_id,pharmacy_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_posm_device_staff_expiry ON posm_device_staff_sessions(expires_at);

-- Separate triggers preserve the existing strict-session revocation contract, including older writers.
CREATE FUNCTION posm_device_staff_revoke_pharmacist_sessions() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status OR OLD.pharmacy_id IS DISTINCT FROM NEW.pharmacy_id
       OR OLD.password_hash IS DISTINCT FROM NEW.password_hash THEN
        UPDATE posm_device_staff_sessions SET revoked_at=now()
        WHERE pharmacist_id=NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_posm_device_staff_pharmacist_revoke
AFTER UPDATE OF status,pharmacy_id,password_hash ON pharmacists
FOR EACH ROW EXECUTE FUNCTION posm_device_staff_revoke_pharmacist_sessions();
CREATE FUNCTION posm_device_staff_revoke_device_sessions() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.active IS DISTINCT FROM NEW.active OR OLD.secret_sha256 IS DISTINCT FROM NEW.secret_sha256
       OR OLD.pharmacy_id IS DISTINCT FROM NEW.pharmacy_id THEN
        UPDATE posm_device_staff_sessions SET revoked_at=now()
        WHERE device_id=OLD.device_id AND pharmacy_id=OLD.pharmacy_id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_posm_device_staff_device_revoke
AFTER UPDATE OF active,secret_sha256,pharmacy_id ON fulfillment_devices
FOR EACH ROW EXECUTE FUNCTION posm_device_staff_revoke_device_sessions();
