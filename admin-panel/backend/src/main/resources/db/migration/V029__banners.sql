-- V029 — баннеры главного экрана мобильного приложения фармацевта (п.5).
--
-- Баннер ведёт ТОЛЬКО на детальный экран внутри приложения (решение продукта):
-- никаких target-полей на товар/кампанию/URL. Контент: картинка (загрузка в MinIO,
-- тот же бакет, что и слайды экранов) + заголовок + подзаголовок + детальный markdown.
--
-- Лента мобилки берёт status='active' ORDER BY position (как playlists/slides).
-- Админка видит все статусы (active/draft/archived) для управления.

CREATE TABLE banners (
    id          VARCHAR(64)  PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    subtitle    VARCHAR(512),
    image_url   VARCHAR(512) NOT NULL DEFAULT '',
    detail_md   TEXT,
    status      VARCHAR(32)  NOT NULL,
    position    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE banners
    ADD CONSTRAINT ck_banners_status CHECK (status IN ('active', 'draft', 'archived'));

CREATE INDEX ix_banners_status   ON banners (status);
CREATE INDEX ix_banners_position ON banners (position);
