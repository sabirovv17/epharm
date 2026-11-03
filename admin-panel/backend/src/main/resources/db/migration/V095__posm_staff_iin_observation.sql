-- Device-reported Standard-N identity is diagnostic, never a financial identity binding.
ALTER TABLE posm_staff_sessions
    ADD COLUMN standard_n_iin VARCHAR(12),
    ADD COLUMN standard_n_iin_status VARCHAR(16) NOT NULL DEFAULT 'unavailable',
    ADD CONSTRAINT ck_posm_staff_source_iin CHECK (
        (standard_n_iin_status = 'available' AND standard_n_iin IS NOT NULL AND standard_n_iin ~ '^[0-9]{12}$') OR
        (standard_n_iin_status IN ('missing','invalid','unavailable','ambiguous') AND standard_n_iin IS NULL)
    );
ALTER TABLE pharmacists
    ADD COLUMN standard_n_iin_match_status VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (standard_n_iin_match_status IN ('MATCH','MISMATCH','UNKNOWN')),
    ADD COLUMN standard_n_iin_checked_at TIMESTAMPTZ;
