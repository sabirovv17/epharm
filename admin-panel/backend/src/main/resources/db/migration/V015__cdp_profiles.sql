-- ТЗ §4 / §5.6 — CDP (карта/профиль клиента) для программы лояльности.
-- На кассе фармацевт вводит телефон клиента → поиск профиля; если нет — создаёт новый
-- (welcome-бонус + SMS со ссылкой на приложение — заглушка до Stage 4 / Mobizon).

CREATE TABLE cdp_profiles (
    id                       VARCHAR(64) PRIMARY KEY,
    phone                    VARCHAR(32) NOT NULL UNIQUE,
    name                     VARCHAR(255),
    tier                     VARCHAR(32) NOT NULL DEFAULT 'Bronze',
    registered_by_pharmacist VARCHAR(64),
    registered_at_pharmacy   VARCHAR(64),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
