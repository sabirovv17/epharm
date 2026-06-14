-- V025 — Кампания как единый источник: товар (1:1), override фото/описания,
-- цена из Medusa (read-only, обновляется планировщиком), правила замены/кросс-селла из кампании.
--
-- Задачи:
--  T1: промо хранит override-фото и override-описание (если PIM-данные хочется заменить вручную);
--      цена/бонус остаются в promos.tiers (один порог {minQty:1, price:<Medusa>, bonus:<админ>}).
--  T1/T2: товары каталога получают ссылку на Medusa-товар — по ней планировщик раз в день
--         обновляет products.price (блок рекомендаций всегда с актуальной ценой).
--  T2: правило ссылается на кампанию (promo_id) — правила замены/кросс-селла генерятся из карточки кампании.

-- ── promos: ручные override-поля (фото/описание) ──────────────────────────────
ALTER TABLE promos
    ADD COLUMN IF NOT EXISTS override_image       VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS override_description  TEXT;

COMMENT ON COLUMN promos.override_image IS
    'Ручная замена фото товара (приоритет над Medusa/снимком). NULL = берём из Medusa.';
COMMENT ON COLUMN promos.override_description IS
    'Ручная замена описания товара (приоритет над Medusa). NULL = берём из Medusa.';

-- ── products: ссылка на товар витрины Medusa (для ежедневного рефреша цены) ────
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS medusa_product_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_products_medusa_id
    ON products(medusa_product_id)
    WHERE medusa_product_id IS NOT NULL;

COMMENT ON COLUMN products.medusa_product_id IS
    'Id товара Medusa, если товар каталога — это позиция витрины. Планировщик обновляет price по нему.';

-- ── rules: привязка к кампании (правила генерятся из карточки кампании) ────────
ALTER TABLE rules
    ADD COLUMN IF NOT EXISTS promo_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_rules_promo_id
    ON rules(promo_id)
    WHERE promo_id IS NOT NULL;

COMMENT ON COLUMN rules.promo_id IS
    'Кампания (promos.id), из которой сгенерировано правило. NULL = ручное правило (legacy).';
