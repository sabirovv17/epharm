-- V002 — auth schema: admin_users + refresh_tokens.
-- Источник дизайна: admin-panel/PLAN.md → Этап 3.1 + claude-admin-notes.md.
--
-- admin_users — пользователи HQ-консоли (категорийная команда Inkar, бренд-менеджеры).
-- НЕ путать с фармацевтами (`pharmacists` будет в V004) — у них другой flow аутентификации
-- через SMS-OTP.

CREATE TABLE admin_users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    role            VARCHAR(64)  NOT NULL,
    company         VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Email регистронезависимо уникальный — login делаем по trim+lower.
CREATE UNIQUE INDEX ux_admin_users_email_lower ON admin_users (LOWER(email));

-- Constraint на role — проще хранить как VARCHAR + check, чем создавать ENUM-тип.
-- ENUM-типы в Postgres трудно расширять, а строковые значения легко добавлять миграциями.
ALTER TABLE admin_users
    ADD CONSTRAINT ck_admin_users_role
    CHECK (role IN ('HQ_HEAD', 'CATEGORY_LEAD', 'BRAND_MANAGER', 'FINANCE_REVIEWER'));

ALTER TABLE admin_users
    ADD CONSTRAINT ck_admin_users_status
    CHECK (status IN ('active', 'pending', 'blocked'));


-- refresh_tokens — хранятся как SHA-256 хеш raw-токена.
-- Raw-токен видим только клиент; на сервере живёт только хеш, чтобы утечка БД не дала
-- готовых refresh-токенов злоумышленнику.
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES admin_users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
