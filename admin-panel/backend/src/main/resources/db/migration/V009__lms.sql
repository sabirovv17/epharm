-- V009 — LMS: каталог обучающих курсов для фармацевтов (ТЗ §3.2 / §3.4).
--
-- Плоская таблица курсов на MVP. Уроки (lessons) и прогресс (course_progress)
-- появятся отдельными таблицами, когда мобилка начнёт реально проходить курсы
-- (Этап 4/6). Сейчас держим denormalized счётчики lessons/enrolled/completed
-- для карточек админки.
--
-- ID — slug ('crs_<short>') аналогично rules / products / promos.

CREATE TABLE courses (
    id           VARCHAR(64)  PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    category     VARCHAR(128) NOT NULL DEFAULT '',
    lessons      INT          NOT NULL DEFAULT 0,
    duration_min INT          NOT NULL DEFAULT 0,
    enrolled     INT          NOT NULL DEFAULT 0,
    completed    INT          NOT NULL DEFAULT 0,
    bonus        INT          NOT NULL DEFAULT 0,
    created_by   VARCHAR(64)  NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE courses
    ADD CONSTRAINT ck_courses_status         CHECK (status IN ('published', 'draft', 'archived')),
    ADD CONSTRAINT ck_courses_lessons_nonneg CHECK (lessons   >= 0),
    ADD CONSTRAINT ck_courses_dur_nonneg     CHECK (duration_min >= 0),
    ADD CONSTRAINT ck_courses_enrolled_nonneg CHECK (enrolled  >= 0),
    ADD CONSTRAINT ck_courses_completed_nonneg CHECK (completed >= 0),
    ADD CONSTRAINT ck_courses_bonus_nonneg   CHECK (bonus     >= 0);

CREATE INDEX ix_courses_status     ON courses (status);
CREATE INDEX ix_courses_updated_at ON courses (updated_at DESC);
