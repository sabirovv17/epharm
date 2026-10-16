ALTER TABLE course_lessons
    ADD COLUMN IF NOT EXISTS live_provider VARCHAR(24),
    ADD COLUMN IF NOT EXISTS live_status VARCHAR(24),
    ADD COLUMN IF NOT EXISTS live_start_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS live_end_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS live_chat_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS live_qa_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS live_reactions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS live_polls JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS course_live_messages (
    id UUID PRIMARY KEY,
    lesson_id VARCHAR(64) NOT NULL REFERENCES course_lessons(id) ON DELETE CASCADE,
    author_type VARCHAR(24) NOT NULL,
    author_id VARCHAR(128) NOT NULL,
    author_name VARCHAR(255) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_course_live_messages_lesson_created
    ON course_live_messages(lesson_id, created_at DESC);

CREATE TABLE IF NOT EXISTS course_live_poll_votes (
    id UUID PRIMARY KEY,
    lesson_id VARCHAR(64) NOT NULL REFERENCES course_lessons(id) ON DELETE CASCADE,
    poll_id VARCHAR(64) NOT NULL,
    voter_id VARCHAR(128) NOT NULL,
    option_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_course_live_poll_vote UNIQUE (lesson_id, poll_id, voter_id)
);

CREATE INDEX IF NOT EXISTS idx_course_live_poll_votes_lesson_poll
    ON course_live_poll_votes(lesson_id, poll_id);

CREATE TABLE IF NOT EXISTS course_live_reactions (
    id UUID PRIMARY KEY,
    lesson_id VARCHAR(64) NOT NULL REFERENCES course_lessons(id) ON DELETE CASCADE,
    actor_id VARCHAR(128) NOT NULL,
    reaction VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_course_live_reactions_lesson_created
    ON course_live_reactions(lesson_id, created_at DESC);
