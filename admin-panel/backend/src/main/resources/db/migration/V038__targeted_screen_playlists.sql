-- Default + targeted POSM playlists.
--
-- pl_broadcast remains the default 12-slot playlist for every pharmacy. A child
-- playlist stores only slot overrides and inherits every missing position from
-- its parent. Assignments are pharmacy based, so every register configured with
-- the same pharmacyId receives the same effective playlist without a POSM update.

ALTER TABLE playlists
    ADD COLUMN parent_playlist_id VARCHAR(64)
        REFERENCES playlists (id) ON DELETE SET NULL;

CREATE INDEX ix_playlists_parent
    ON playlists (parent_playlist_id);

CREATE TABLE playlist_pharmacy_assignments (
    pharmacy_id VARCHAR(64) PRIMARY KEY
        REFERENCES pharmacies (id) ON DELETE CASCADE,
    playlist_id VARCHAR(64) NOT NULL
        REFERENCES playlists (id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_playlist_pharmacy_assignments_playlist
    ON playlist_pharmacy_assignments (playlist_id, pharmacy_id);

-- Fresh databases do not have the runtime-created broadcast row yet. Existing
-- production rows and their slides remain untouched by ON CONFLICT DO NOTHING.
INSERT INTO playlists (
    id,
    name,
    status,
    slides_count,
    duration_sec,
    pharmacies
)
VALUES ('pl_broadcast', 'Эфир касс', 'active', 0, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- The child row is created without touching pl_broadcast or its existing slides.
INSERT INTO playlists (
    id,
    name,
    status,
    slides_count,
    duration_sec,
    pharmacies,
    parent_playlist_id
)
VALUES (
    'pl_broadcast_targeted',
    'Индивидуальный плейлист',
    'active',
    0,
    0,
    0,
    'pl_broadcast'
)
ON CONFLICT (id) DO UPDATE
SET parent_playlist_id = EXCLUDED.parent_playlist_id,
    status = 'active';
