-- Structured online-course content.
-- Existing course rows and their legacy counters are preserved. As soon as a course
-- receives structured lessons, the application keeps lessons/duration_min in sync.

ALTER TABLE courses
    ADD COLUMN description TEXT NOT NULL DEFAULT '';

CREATE TABLE course_lessons (
    id           VARCHAR(64)   PRIMARY KEY,
    course_id    VARCHAR(64)   NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title        VARCHAR(255)  NOT NULL,
    description  VARCHAR(1000) NOT NULL DEFAULT '',
    content      TEXT          NOT NULL DEFAULT '',
    kind         VARCHAR(16)   NOT NULL DEFAULT 'text',
    video_url    VARCHAR(1000),
    duration_min INT           NOT NULL DEFAULT 0,
    order_no     INT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_course_lessons_kind CHECK (kind IN ('text', 'video')),
    CONSTRAINT ck_course_lessons_duration_nonneg CHECK (duration_min >= 0),
    CONSTRAINT ck_course_lessons_order_nonneg CHECK (order_no >= 0)
);

CREATE INDEX ix_course_lessons_course_order
    ON course_lessons (course_id, order_no, created_at);
