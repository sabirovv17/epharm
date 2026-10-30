-- Native operational history. Legacy visit reports remain immutable source snapshots.
ALTER TABLE merch_task_items ADD COLUMN blocked_reason text CHECK(blocked_reason IS NULL OR length(btrim(blocked_reason)) BETWEEN 3 AND 1200);
CREATE TABLE merch_task_comments (
    id uuid PRIMARY KEY,
    item_id uuid NOT NULL REFERENCES merch_task_items(id) ON DELETE RESTRICT,
    author_person_id uuid NOT NULL REFERENCES unified_persons(id) ON DELETE RESTRICT,
    author_account_id uuid REFERENCES admin_users(id) ON DELETE RESTRICT,
    pharmacist_id varchar(64) REFERENCES pharmacists(id) ON DELETE RESTRICT,
    body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 1200),
    created_at timestamptz NOT NULL,
    CHECK((author_account_id IS NOT NULL) <> (pharmacist_id IS NOT NULL))
);
CREATE INDEX ix_merch_comments_item ON merch_task_comments(item_id,created_at,id);
CREATE TRIGGER immutable_merch_task_comments BEFORE UPDATE OR DELETE ON merch_task_comments
    FOR EACH ROW EXECUTE FUNCTION preserve_merch_task_evidence();
CREATE TABLE merch_monthly_visits (
    branch_id uuid NOT NULL REFERENCES merch_branches(id) ON DELETE RESTRICT,
    placement_month date NOT NULL CHECK(extract(day FROM placement_month)=1),
    executor_person_id uuid NOT NULL REFERENCES unified_persons(id) ON DELETE RESTRICT,
    dispatch_id uuid NOT NULL UNIQUE REFERENCES merch_task_dispatches(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(branch_id,placement_month,executor_person_id)
);
CREATE TABLE merch_task_template_receipts (
    id uuid PRIMARY KEY,
    created_by uuid NOT NULL REFERENCES admin_users(id) ON DELETE RESTRICT,
    request_payload jsonb NOT NULL,
    dispatch_ids jsonb NOT NULL,
    created_at timestamptz NOT NULL
);
