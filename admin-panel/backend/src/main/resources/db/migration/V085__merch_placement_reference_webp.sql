-- Keep V081 and all original bytes/hashes immutable; extend only accepted reference MIME metadata.
ALTER TABLE merch_placement_reference_photos DROP CONSTRAINT merch_placement_reference_photos_mime_type_check;
ALTER TABLE merch_placement_reference_photos ADD CONSTRAINT merch_placement_reference_photos_mime_type_check
    CHECK (mime_type IN ('image/jpeg','image/png','image/webp'));
