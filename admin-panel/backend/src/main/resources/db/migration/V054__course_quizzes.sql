ALTER TABLE course_lessons
    ADD COLUMN quiz_questions JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN quiz_passing_score INTEGER NOT NULL DEFAULT 80;

ALTER TABLE course_lessons
    ADD CONSTRAINT ck_course_lessons_quiz_passing_score
        CHECK (quiz_passing_score BETWEEN 1 AND 100);

ALTER TABLE training_lesson_progress
    ADD COLUMN quiz_score INTEGER,
    ADD COLUMN quiz_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE training_lesson_progress
    ADD CONSTRAINT ck_training_lesson_progress_quiz_score
        CHECK (quiz_score IS NULL OR quiz_score BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_training_lesson_progress_quiz_attempts
        CHECK (quiz_attempts >= 0);
