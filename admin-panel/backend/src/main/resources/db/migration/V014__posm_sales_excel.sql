-- ТЗ §3.5 Stage 2 — сверка чека по 3 источникам.
--
-- pos_sales:        источник №1 — завершённый чек, подтверждённый логом кассы Стандарт-Н
--                   (POSM-клиент шлёт после печати в /api/posm/sales).
-- excel_imports / excel_sale_rows: источник №2 — ежедневная Excel-выгрузка из менеджерской
--                   части Стандарт-Н, загружается через админку /api/admin/reconcile/import-excel.
-- receipts расширяется: confirmed_by_log / confirmed_by_excel + источник + статус
--                   moderation_required (подтверждён только одним источником → ручная проверка).

ALTER TABLE receipts ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'photo';
ALTER TABLE receipts ADD COLUMN confirmed_by_log BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE receipts ADD COLUMN confirmed_by_excel BOOLEAN NOT NULL DEFAULT false;

-- Новый статус moderation_required (источник №3 — ручная модерация).
ALTER TABLE receipts DROP CONSTRAINT receipts_status_check;
ALTER TABLE receipts ADD CONSTRAINT receipts_status_check
    CHECK (status IN ('pending', 'moderation_required', 'flagged', 'approved', 'rejected'));

CREATE TABLE pos_sales (
    id            VARCHAR(64) PRIMARY KEY,          -- client-GUID (идемпотентность ретраев outbox)
    session_id    VARCHAR(64),
    pharmacist_id VARCHAR(64) NOT NULL,
    pharmacy_id   VARCHAR(64) NOT NULL,
    fiscal_id     VARCHAR(128),
    cashier       VARCHAR(128),
    shift         VARCHAR(64),
    total_amount  BIGINT NOT NULL DEFAULT 0,
    items         JSONB NOT NULL DEFAULT '[]',      -- [{sku,name,qty,price,total}]
    printed_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pos_sales_pharmacist ON pos_sales (pharmacist_id);
CREATE INDEX idx_pos_sales_fiscal ON pos_sales (fiscal_id);

CREATE TABLE excel_imports (
    id           VARCHAR(64) PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    uploaded_by  VARCHAR(64)  NOT NULL,
    rows_total   INT NOT NULL DEFAULT 0,
    rows_matched INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE excel_sale_rows (
    id            VARCHAR(64) PRIMARY KEY,
    import_id     VARCHAR(64) NOT NULL REFERENCES excel_imports (id) ON DELETE CASCADE,
    fiscal_id     VARCHAR(128),
    pharmacy_code VARCHAR(64),
    cashier       VARCHAR(128),
    sku           VARCHAR(64),
    product_name  VARCHAR(255),
    qty           NUMERIC(12,3),
    amount        BIGINT,
    sold_at       TIMESTAMPTZ,
    matched       BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_excel_rows_import ON excel_sale_rows (import_id);
CREATE INDEX idx_excel_rows_fiscal ON excel_sale_rows (fiscal_id);
