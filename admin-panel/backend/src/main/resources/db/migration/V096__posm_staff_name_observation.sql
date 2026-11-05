-- Device-reported names remain diagnostic and never establish a financial seller mapping.
ALTER TABLE posm_staff_sessions
    ADD COLUMN standard_n_full_name VARCHAR(255),
    ADD COLUMN standard_n_full_name_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    ADD CONSTRAINT ck_posm_staff_source_name CHECK (
        (standard_n_full_name_status = 'available' AND standard_n_full_name IS NOT NULL
            AND length(btrim(standard_n_full_name)) BETWEEN 1 AND 255) OR
        (standard_n_full_name_status IN ('missing','invalid','unavailable','ambiguous') AND standard_n_full_name IS NULL)
    );
ALTER TABLE pharmacists
    ADD COLUMN standard_n_identity_match_method VARCHAR(8) NOT NULL DEFAULT 'NONE'
        CHECK (standard_n_identity_match_method IN ('IIN','NAME','NONE')),
    ADD COLUMN standard_n_identity_match_status VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (standard_n_identity_match_status IN ('MATCH','MISMATCH','UNKNOWN')),
    ADD COLUMN standard_n_identity_checked_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_pharmacist_identity_observation CHECK (
        (standard_n_identity_match_method = 'NONE' AND standard_n_identity_match_status = 'UNKNOWN') OR
        (standard_n_identity_match_method IN ('IIN','NAME') AND standard_n_identity_match_status IN ('MATCH','MISMATCH'))
    );
