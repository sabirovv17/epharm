-- URL and SHA-256 come through one API response, so an offline signature anchors both fields to
-- a public key pinned independently in every POSM installation.
ALTER TABLE app_releases
    ADD COLUMN manifest_signature VARCHAR(256) NOT NULL DEFAULT '';
