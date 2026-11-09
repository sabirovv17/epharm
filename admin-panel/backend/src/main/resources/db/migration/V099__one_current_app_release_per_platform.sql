-- A release publication also takes a transaction-scoped advisory lock per platform.
-- The constraint protects the invariant from every other writer, too.
-- Intentionally fail on pre-existing duplicates: do not silently choose a signed release.
CREATE UNIQUE INDEX uq_app_releases_current_platform
    ON app_releases(platform) WHERE is_current;
