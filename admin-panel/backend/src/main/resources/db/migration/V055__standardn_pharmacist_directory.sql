CREATE TABLE standardn_pharmacist_directory (
    directory_key      VARCHAR(128) PRIMARY KEY,
    source_profile_id  BIGINT       NOT NULL,
    source_user_id     BIGINT       NOT NULL,
    iin                VARCHAR(12)  NOT NULL,
    full_name          VARCHAR(255) NOT NULL,
    source_status      INT          NOT NULL,
    source_login       VARCHAR(255),
    source_post        VARCHAR(255),
    source_department  VARCHAR(255),
    profile_name       VARCHAR(255),
    profile_city       VARCHAR(128),
    profile_address    VARCHAR(512),
    imported_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_standardn_directory_iin CHECK (iin ~ '^[0-9]{12}$'),
    CONSTRAINT ux_standardn_directory_source UNIQUE (source_profile_id, source_user_id)
);

CREATE INDEX ix_standardn_directory_iin
    ON standardn_pharmacist_directory (iin);
CREATE INDEX ix_standardn_directory_active_iin
    ON standardn_pharmacist_directory (iin)
    WHERE source_status = 0;

COMMENT ON TABLE standardn_pharmacist_directory IS
    'Read-only mirror of Standard-N USERS. Source Firebird is never modified.';
