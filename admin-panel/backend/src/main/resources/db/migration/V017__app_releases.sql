-- V017 — авто-обновление POSM-клиента на кассе (Windows).
--
-- Касса периодически опрашивает GET /api/posm/app/version. Если version в текущем релизе
-- новее установленной — клиент сам скачивает zip (url), проверяет sha256 и обновляется.
-- Так не нужно вручную пересобирать/копировать приложение на каждую кассу.
--
-- is_current — ровно один «текущий» релиз на платформу (выставляется в сервисе транзакционно:
-- снять флаг со старых → поставить новому). mandatory — обязательное обновление (клиент не
-- откладывает). url — обычно объект в MinIO (та же инфраструктура, что и медиа слайдов).

CREATE TABLE app_releases (
    id          VARCHAR(64)   PRIMARY KEY,
    platform    VARCHAR(32)   NOT NULL DEFAULT 'win-x64',
    version     VARCHAR(32)   NOT NULL,
    url         VARCHAR(512)  NOT NULL,
    sha256      VARCHAR(128)  NOT NULL DEFAULT '',
    mandatory   BOOLEAN       NOT NULL DEFAULT false,
    notes       VARCHAR(1024) NOT NULL DEFAULT '',
    is_current  BOOLEAN       NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Быстрый подбор текущего релиза для платформы.
CREATE INDEX ix_app_releases_current ON app_releases (platform, is_current);
