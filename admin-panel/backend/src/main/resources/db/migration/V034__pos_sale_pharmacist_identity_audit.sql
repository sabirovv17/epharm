-- Keep the exact Standard-N cashier identity alongside the trusted internal ePharm identity.
-- An external USER_ID is not automatically trusted for bonuses until it is deterministically
-- matched to an active pharmacist assigned to the same pharmacy.
ALTER TABLE pos_sales
    ADD COLUMN reported_pharmacist_id   VARCHAR(255),
    ADD COLUMN reported_pharmacist_name VARCHAR(255),
    ADD COLUMN pharmacist_source        VARCHAR(32) NOT NULL DEFAULT 'legacy';

ALTER TABLE pos_sales
    ADD CONSTRAINT ck_pos_sales_pharmacist_source CHECK (
        pharmacist_source IN (
            'legacy',
            'posm_internal',
            'standardn_name_match',
            'standardn_unmapped',
            'unresolved'
        )
    );
