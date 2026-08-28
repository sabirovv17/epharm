-- POSM receipt-capture audit metadata. source_document_id is local Standard-N DOCS.ID,
-- not a fiscal receipt number. Product ids are stored inside the existing items JSONB objects,
-- so that part is backward-compatible and needs no table rewrite.

ALTER TABLE pos_sales
    ADD COLUMN source_document_id BIGINT,
    ADD COLUMN capture_source VARCHAR(64),
    ADD COLUMN artifact_format VARCHAR(16);

CREATE INDEX idx_pos_sales_pharmacy_source_document
    ON pos_sales (pharmacy_id, source_document_id)
    WHERE source_document_id IS NOT NULL;
