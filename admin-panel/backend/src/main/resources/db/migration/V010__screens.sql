-- V010 — indoor-DOOH экраны (ТЗ §3.3 / §4): плейлисты + библиотека слайдов.
--
-- Этап 3.6 — read-модель: админка показывает, что заведено. Полный редактор
-- плейлистов + загрузка медиа в S3 + рендер на втором мониторе — Этап 5 (POSM).
--
-- slides.playlist_id — nullable: слайд может лежать в библиотеке вне плейлиста.
-- ON DELETE SET NULL: удаление плейлиста не уносит слайды из библиотеки.

CREATE TABLE playlists (
    id           VARCHAR(64)  PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    slides_count INT          NOT NULL DEFAULT 0,
    duration_sec INT          NOT NULL DEFAULT 0,
    pharmacies   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE playlists
    ADD CONSTRAINT ck_playlists_status CHECK (status IN ('active', 'draft', 'archived')),
    ADD CONSTRAINT ck_playlists_slides_nonneg CHECK (slides_count >= 0),
    ADD CONSTRAINT ck_playlists_dur_nonneg    CHECK (duration_sec >= 0);

CREATE INDEX ix_playlists_status     ON playlists (status);
CREATE INDEX ix_playlists_updated_at ON playlists (updated_at DESC);

CREATE TABLE slides (
    id           VARCHAR(64)  PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    kind         VARCHAR(32)  NOT NULL,
    duration_sec INT          NOT NULL DEFAULT 0,
    media_url    VARCHAR(512) NOT NULL DEFAULT '',
    playlist_id  VARCHAR(64)  REFERENCES playlists (id) ON DELETE SET NULL,
    position     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE slides
    ADD CONSTRAINT ck_slides_kind CHECK (kind IN ('video', 'image')),
    ADD CONSTRAINT ck_slides_dur_nonneg CHECK (duration_sec >= 0);

CREATE INDEX ix_slides_playlist ON slides (playlist_id);
CREATE INDEX ix_slides_kind     ON slides (kind);
