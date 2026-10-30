-- Extend only new inventory image metadata. Original files, hashes, source payloads and V078 remain unchanged.
ALTER TABLE merch_inventory_media DROP CONSTRAINT merch_inventory_media_mime_type_check;
ALTER TABLE merch_inventory_media ADD CONSTRAINT merch_inventory_media_mime_type_check
    CHECK (mime_type IN ('image/jpeg','image/png','image/webp'));
