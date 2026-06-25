BEGIN;

-- Keep the admin panel focused: only POSM demo campaigns used by the cashier
-- scenario remain active. Older demo/storefront campaigns are archived so the
-- active promo list is not ambiguous during acceptance testing.
UPDATE promos
SET status = 'archived',
    updated_at = now()
WHERE status <> 'archived'
  AND id NOT IN ('pr_ecfd80ce', 'pr_f4b2b5b6', 'pr_6e2f0013', 'pr_26909b11');

UPDATE rules
SET status = 'archived',
    updated_at = now()
WHERE status <> 'archived'
  AND (promo_id IS NULL OR promo_id NOT IN ('pr_ecfd80ce', 'pr_f4b2b5b6', 'pr_6e2f0013', 'pr_26909b11'));

-- Clear, ordered names in the promo list.
UPDATE promos
SET title = 'POSM 01 — Цетрин: замена Супрастина + кросс-селл с Фенистилом',
    period = 'POSM demo',
    kpi = 'Супрастин -> Цетрин; Фенистил в чеке -> добавить Цетрин',
    updated_at = now()
WHERE id = 'pr_ecfd80ce';

UPDATE promos
SET title = 'POSM 02 — Геделикс: замена Гастросидина',
    period = 'POSM demo',
    kpi = 'Гастросидин -> Геделикс',
    updated_at = now()
WHERE id = 'pr_f4b2b5b6';

UPDATE promos
SET title = 'POSM 03 — Иммунал: замена Ликопида',
    period = 'POSM demo',
    kpi = 'Ликопид -> Иммунал',
    updated_at = now()
WHERE id = 'pr_6e2f0013';

UPDATE promos
SET title = 'POSM 04 — Цинкит: кросс-селл к Ликопиду',
    period = 'POSM demo',
    kpi = 'Ликопид в чеке -> добавить Цинкит',
    updated_at = now()
WHERE id = 'pr_26909b11';

-- Make sure campaign-backed active rules keep the intended active status.
UPDATE rules r
SET status = CASE
        WHEN p.status = 'active' AND COALESCE((r.card->>'pairActive')::boolean, true) THEN 'active'
        ELSE 'archived'
    END,
    updated_at = now()
FROM promos p
WHERE r.promo_id = p.id;

-- The promo list is sorted by updated_at DESC. Keep the active admin list readable:
-- POSM 01, then 02, 03, 04.
UPDATE promos SET updated_at = now() + interval '4 seconds' WHERE id = 'pr_ecfd80ce';
UPDATE promos SET updated_at = now() + interval '3 seconds' WHERE id = 'pr_f4b2b5b6';
UPDATE promos SET updated_at = now() + interval '2 seconds' WHERE id = 'pr_6e2f0013';
UPDATE promos SET updated_at = now() + interval '1 second' WHERE id = 'pr_26909b11';

COMMIT;
