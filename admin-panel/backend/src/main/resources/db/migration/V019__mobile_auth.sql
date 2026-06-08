-- V019 — мобильная (фармацевт) аутентификация: OTP-стор + refresh-токены + nullable аптека.
--
-- Контекст: до этой миграции фармацевт существовал только как запись, заведённая админом
-- (Этап 5, CDP-форма). Теперь — саморегистрация из мобильного приложения:
--   phone → OTP → (если номер новый) экран ФИО+ИИН → pharmacist(status='pending').
-- Админ в HQ-консоли привязывает pending-фармацевта к аптеке/тиру и активирует.
--
-- Источник дизайна: claude-admin-notes.md → «Мобильный бэкенд», PLAN.md Этап 3.2 + 6.

-- 1) Саморегистрированный pending-фармацевт ещё НЕ привязан к аптеке → pharmacy_id/name nullable.
--    FK на pharmacies сохраняем — NULL его не нарушает; "" нарушал бы (нет такой аптеки).
--    Активный фармацевт всегда имеет аптеку (проверяется в сервис-слое при активации).
ALTER TABLE pharmacists ALTER COLUMN pharmacy_id   DROP NOT NULL;
ALTER TABLE pharmacists ALTER COLUMN pharmacy_name DROP NOT NULL;

-- 2) OTP-стор. Намеренно в БД, а не в Redis: интеграционные тесты идут на Postgres
--    Testcontainers, Redis в коде нигде не используется. Durable + тестируемо без нового
--    контейнера. TTL/попытки обслуживаются в OtpService (поле expires_at + attempts).
--    Один номер = одна активная строка (phone PRIMARY KEY, upsert при повторном запросе).
--    code_hash = SHA-256 от кода — в БД не лежит сам код (как и refresh-токены).
CREATE TABLE mobile_otps (
    phone        VARCHAR(32)  PRIMARY KEY,
    code_hash    VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    -- verified_at != null → код подтверждён; даёт окно на /register (15 мин в сервисе).
    verified_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 3) Refresh-токены фармацевтов. Отдельная таблица от admin refresh_tokens, потому что
--    та FK-завязана на admin_users(id) UUID, а pharmacist.id — VARCHAR(64).
--    Механика идентична: raw на клиенте, SHA-256 hash в БД, rotation при refresh.
CREATE TABLE mobile_refresh_tokens (
    id            UUID PRIMARY KEY,
    pharmacist_id VARCHAR(64)  NOT NULL REFERENCES pharmacists (id) ON DELETE CASCADE,
    token_hash    VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_mobile_refresh_tokens_hash       ON mobile_refresh_tokens (token_hash);
CREATE INDEX        ix_mobile_refresh_tokens_pharmacist ON mobile_refresh_tokens (pharmacist_id);
