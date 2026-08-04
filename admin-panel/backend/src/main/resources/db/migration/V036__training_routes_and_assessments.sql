-- V036 - explicit per-format routes and trusted assessment history.

ALTER TABLE training_program_stages
    ADD COLUMN applicable_formats VARCHAR(64),
    ADD COLUMN content_url VARCHAR(1000);

UPDATE training_program_stages
SET applicable_formats = CASE
    WHEN type IN ('material', 'online_course') THEN 'online,hybrid'
    WHEN type = 'offline_event' THEN 'hybrid,offline'
    ELSE 'online,hybrid,offline'
END;

ALTER TABLE training_program_stages
    ALTER COLUMN applicable_formats SET NOT NULL;

ALTER TABLE training_program_stages
    ADD CONSTRAINT ck_training_stage_formats CHECK (length(trim(applicable_formats)) > 0);

CREATE TABLE training_assessment_results (
    id                   UUID PRIMARY KEY,
    assignment_id        UUID NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    assignment_stage_id  UUID NOT NULL REFERENCES training_assignment_stages (id) ON DELETE CASCADE,
    source_type          VARCHAR(32) NOT NULL,
    attempt_no           INT NOT NULL,
    score                INT NOT NULL,
    passed               BOOLEAN NOT NULL,
    feedback             VARCHAR(2000) NOT NULL DEFAULT '',
    competency_json      TEXT NOT NULL DEFAULT '{}',
    recorded_by          UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assignment_stage_id, attempt_no),
    CONSTRAINT ck_training_assessment_source CHECK (source_type IN ('test', 'ai_exam', 'manual_review')),
    CONSTRAINT ck_training_assessment_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_training_assessment_score CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX ix_training_assessment_assignment
    ON training_assessment_results (assignment_id, recorded_at DESC);

CREATE TABLE training_assignment_format_history (
    id             UUID PRIMARY KEY,
    assignment_id  UUID NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    old_format     VARCHAR(16) NOT NULL,
    new_format     VARCHAR(16) NOT NULL,
    old_event_id   UUID REFERENCES offline_events (id) ON DELETE SET NULL,
    new_event_id   UUID REFERENCES offline_events (id) ON DELETE SET NULL,
    reason         VARCHAR(500) NOT NULL,
    changed_by     UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    changed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_format_history_old CHECK (old_format IN ('online', 'hybrid', 'offline')),
    CONSTRAINT ck_training_format_history_new CHECK (new_format IN ('online', 'hybrid', 'offline'))
);

CREATE INDEX ix_training_format_history_assignment
    ON training_assignment_format_history (assignment_id, changed_at DESC);
