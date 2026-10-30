-- New tasks pin a private immutable photo version; historical source references remain untouched.
ALTER TABLE merch_placement_reference_photos ADD CONSTRAINT uq_merch_photo_item UNIQUE(id,media_plan_item_id);
ALTER TABLE merch_task_items ADD COLUMN client_reference_photo_id uuid,
    ADD CONSTRAINT ck_merch_task_photo_parent CHECK(client_reference_photo_id IS NULL OR media_plan_item_id IS NOT NULL),
    ADD CONSTRAINT fk_merch_task_reference_photo FOREIGN KEY(client_reference_photo_id,media_plan_item_id)
        REFERENCES merch_placement_reference_photos(id,media_plan_item_id) ON DELETE RESTRICT;
