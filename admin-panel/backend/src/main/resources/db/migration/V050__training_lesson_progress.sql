CREATE TABLE training_lesson_progress (
    id                    UUID PRIMARY KEY,
    assignment_id         UUID NOT NULL REFERENCES training_assignments(id) ON DELETE CASCADE,
    assignment_stage_id   UUID NOT NULL REFERENCES training_assignment_stages(id) ON DELETE CASCADE,
    lesson_id             VARCHAR(64) NOT NULL REFERENCES course_lessons(id) ON DELETE RESTRICT,
    progress_pct          INTEGER NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
    last_position_seconds INTEGER NOT NULL DEFAULT 0 CHECK (last_position_seconds >= 0),
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_training_lesson_progress_stage_lesson UNIQUE (assignment_stage_id, lesson_id)
);

CREATE INDEX ix_training_lesson_progress_assignment
    ON training_lesson_progress (assignment_id, assignment_stage_id);
