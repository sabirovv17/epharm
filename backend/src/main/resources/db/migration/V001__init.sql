-- V001 — init schema marker. Реальные таблицы создаются миграциями V002+ в Этапе 3.
-- Пока что только баннер, чтобы Flyway запустился на пустой БД без ошибки.

CREATE TABLE IF NOT EXISTS schema_meta (
    key   VARCHAR(64) PRIMARY KEY,
    value VARCHAR(256) NOT NULL
);

INSERT INTO schema_meta (key, value) VALUES ('bootstrap_version', '0.1.0')
ON CONFLICT (key) DO NOTHING;
