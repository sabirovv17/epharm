-- V016 — назначение плейлиста на конкретный экран/аптеку (Stage 3 след. итерация).
--
-- Бизнес-запрос: «загрузить видео на ВСЕ экраны» vs «загрузить на конкретный экран».
--   pharmacy_id = NULL  → глобальный плейлист (играет на всех кассах, где нет своего).
--   pharmacy_id = 'ph_x' → плейлист именно этой аптеки (перекрывает глобальный для неё).
--
-- Подбор активного плейлиста для кассы (ScreenService.activePlaylistForScreen):
--   1) активный плейлист с pharmacy_id = <касса>  → берём его (override);
--   2) иначе активный глобальный (pharmacy_id IS NULL) → берём его (default);
--   3) иначе пусто → касса крутит локальный promo.mp4.
--
-- ON DELETE SET NULL: удалили аптеку → её плейлист становится глобальным, не падаем.

ALTER TABLE playlists
    ADD COLUMN pharmacy_id VARCHAR(64) REFERENCES pharmacies (id) ON DELETE SET NULL;

-- Быстрый подбор «активный для аптеки» и «активный глобальный».
CREATE INDEX ix_playlists_active_pharmacy
    ON playlists (pharmacy_id, status, updated_at DESC);
