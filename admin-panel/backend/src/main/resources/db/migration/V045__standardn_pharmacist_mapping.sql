-- Explicit, pharmacy-scoped Standard-N cashier -> ePharm pharmacist identity mapping.
-- External USER_ID values are never globally unique and must not earn bonuses until an HQ user
-- deliberately maps them to an active pharmacist at the same pharmacy.

CREATE TABLE posm_pharmacist_mappings (
    id                UUID PRIMARY KEY,
    pharmacy_id       VARCHAR(64) NOT NULL REFERENCES pharmacies(id) ON DELETE RESTRICT,
    external_user_id  VARCHAR(128) NOT NULL,
    external_user_name VARCHAR(255),
    pharmacist_id     VARCHAR(64) NOT NULL REFERENCES pharmacists(id) ON DELETE RESTRICT,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_by        UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    updated_by        UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    CONSTRAINT ux_posm_pharmacist_mapping_external UNIQUE (pharmacy_id, external_user_id),
    CONSTRAINT ck_posm_pharmacist_mapping_revoke CHECK (
        (active AND revoked_at IS NULL) OR (NOT active AND revoked_at IS NOT NULL)
    )
);

CREATE INDEX ix_posm_pharmacist_mappings_pharmacist
    ON posm_pharmacist_mappings (pharmacist_id, active);

CREATE TABLE posm_pharmacist_mapping_audit (
    id                BIGSERIAL PRIMARY KEY,
    mapping_id        UUID NOT NULL REFERENCES posm_pharmacist_mappings(id) ON DELETE RESTRICT,
    action            VARCHAR(32) NOT NULL,
    pharmacy_id       VARCHAR(64) NOT NULL,
    external_user_id  VARCHAR(128) NOT NULL,
    pharmacist_id     VARCHAR(64) NOT NULL,
    actor_id          UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_posm_mapping_audit_action CHECK (action IN ('created', 'remapped', 'reactivated', 'revoked'))
);

CREATE INDEX ix_posm_mapping_audit_mapping_time
    ON posm_pharmacist_mapping_audit (mapping_id, created_at DESC);

ALTER TABLE pos_sales DROP CONSTRAINT ck_pos_sales_pharmacist_source;
ALTER TABLE pos_sales
    ADD CONSTRAINT ck_pos_sales_pharmacist_source CHECK (
        pharmacist_source IN (
            'legacy',
            'posm_internal',
            'standardn_explicit_mapping',
            'standardn_name_match',
            'standardn_unmapped',
            'unresolved'
        )
    );
