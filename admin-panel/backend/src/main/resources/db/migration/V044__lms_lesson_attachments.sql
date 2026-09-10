CREATE TABLE course_lesson_attachments (
    id           VARCHAR(64)   PRIMARY KEY,
    lesson_id    VARCHAR(64)   NOT NULL REFERENCES course_lessons(id) ON DELETE CASCADE,
    title        VARCHAR(255)  NOT NULL,
    file_name    VARCHAR(255)  NOT NULL,
    content_type VARCHAR(128)  NOT NULL,
    media_url    VARCHAR(1000) NOT NULL,
    size_bytes   BIGINT        NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_course_lesson_attachment_size CHECK (size_bytes >= 0)
);

CREATE INDEX ix_course_lesson_attachments_lesson
    ON course_lesson_attachments (lesson_id, created_at);
