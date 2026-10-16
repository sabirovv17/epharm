ALTER TABLE training_programs
    ADD COLUMN IF NOT EXISTS certificate_epharm_logo_url VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS certificate_partner_logo_url VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS certificate_signer_name VARCHAR(255) NOT NULL DEFAULT 'Руководитель учебного центра',
    ADD COLUMN IF NOT EXISTS certificate_validity_months INTEGER NOT NULL DEFAULT 36,
    ADD COLUMN IF NOT EXISTS certificate_template VARCHAR(64) NOT NULL DEFAULT 'modern_ribbon';

ALTER TABLE training_programs
    ADD CONSTRAINT chk_training_certificate_validity_months
        CHECK (certificate_validity_months BETWEEN 1 AND 120);
