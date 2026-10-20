-- Expand only: the legacy scalar remains readable by older admin/POSM clients.
ALTER TABLE products ADD COLUMN barcodes JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE promos ADD COLUMN barcodes JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE promos ADD COLUMN barcode_source_complete BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE products SET barcodes = jsonb_build_array(trim(barcode))
 WHERE barcode IS NOT NULL AND trim(barcode) <> '';
UPDATE promos SET barcodes = jsonb_build_array(trim(barcode))
 WHERE barcode IS NOT NULL AND trim(barcode) <> '';

ALTER TABLE products ADD CONSTRAINT products_barcodes_array CHECK (jsonb_typeof(barcodes) = 'array');
ALTER TABLE promos ADD CONSTRAINT promos_barcodes_array CHECK (jsonb_typeof(barcodes) = 'array');

-- The website uses lowercase UUIDs while reviewed ACC imports preserve uppercase WARE_IDs.
CREATE INDEX ix_acc_catalog_ware_casefold ON acc_catalog_barcodes (snapshot_id, lower(ware_id));
