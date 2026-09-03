-- Exact fiscal artifact provenance. The binary remains in the isolated POSM retention store;
-- these fields provide an immutable correlation/hash trail without putting receipt PII in the
-- existing public media bucket.
ALTER TABLE pos_sales ADD COLUMN artifact_sha256 VARCHAR(64);
ALTER TABLE pos_sales ADD COLUMN artifact_source VARCHAR(64);
ALTER TABLE pos_sales ADD COLUMN fiscal_sign VARCHAR(128);
ALTER TABLE pos_sales ADD COLUMN cash_register_registration_number VARCHAR(128);
ALTER TABLE pos_sales ADD COLUMN ofd_name VARCHAR(255);

-- Before V042 artifact_format='png' meant a POSM-rendered cart image, never a fiscal original.
UPDATE pos_sales SET artifact_format = NULL WHERE artifact_format = 'png';

CREATE INDEX idx_pos_sales_artifact_sha256 ON pos_sales (artifact_sha256)
    WHERE artifact_sha256 IS NOT NULL;
