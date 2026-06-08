-- V011 — AI-экзаменация (ТЗ §3.4): банк вопросов.
--
-- Этап 3.6 — только банк вопросов (list + create). Сессии экзамена, попытки,
-- keyword-matching и сертификаты придут в Этапе 4 (отдельные таблицы
-- exam_sessions / exam_results / certificates).
--
-- keywords — JSONB массив ожидаемых ключевых слов. На MVP (Этап 4) примитивный
-- KeywordMatcher сверяет ответ фармацевта с этим списком; позже — LLM-оценка.

CREATE TABLE exam_questions (
    id          VARCHAR(64)  PRIMARY KEY,
    prompt      TEXT         NOT NULL,
    kind        VARCHAR(32)  NOT NULL,
    category    VARCHAR(128) NOT NULL DEFAULT '',
    keywords    JSONB        NOT NULL DEFAULT '[]',
    difficulty  INT          NOT NULL DEFAULT 1,
    created_by  VARCHAR(64)  NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE exam_questions
    ADD CONSTRAINT ck_exam_q_kind CHECK (kind IN ('factual', 'comparative', 'scenario')),
    ADD CONSTRAINT ck_exam_q_difficulty CHECK (difficulty BETWEEN 1 AND 5);

CREATE INDEX ix_exam_q_kind       ON exam_questions (kind);
CREATE INDEX ix_exam_q_updated_at ON exam_questions (updated_at DESC);
