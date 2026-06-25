BEGIN;

-- Known Standard-N iPartID values from live POSM logs.
UPDATE products SET ipart_id = '92879', updated_at = now()
WHERE id = 'prod_01KSB3XGHP9V2C0JTJQDTTNJF9'; -- Супрастин

UPDATE products SET ipart_id = '93133', updated_at = now()
WHERE id = 'prod_01KSB3YMDGZVVCC70NYRPH9Y53'; -- Гастросидин

UPDATE products SET ipart_id = '86584', updated_at = now()
WHERE id = 'prod_01KSB3XJ36WF39ZNR3HE25RW02'; -- Ликопид

UPDATE products SET ipart_id = '92272', updated_at = now()
WHERE id = 'prod_01KSB3XMEQC5CHTE8C7QWBR61N'; -- Цетрин

-- Campaign: Cetrin is the promoted product.
-- Substitution: Супрастин -> Цетрин.
-- Cross-sell: Фенистил already in cart -> add Цетрин.
UPDATE promos
SET title = 'Цетрин 10мг таб п/о №20',
    status = 'active',
    medusa_product_id = 'prod_01KSB3XMEQC5CHTE8C7QWBR61N',
    product_name = 'Цетрин 10мг таб п/о №20',
    brand = 'Dr.Reddy`s Laboratories Ltd.',
    barcode = '8901148232327',
    ipart_id = '92272',
    tiers = '[{"minQty":1,"price":0,"bonus":300}]'::jsonb,
    updated_at = now()
WHERE id = 'pr_ecfd80ce';

UPDATE rules
SET trigger = jsonb_build_object('kind','product','value','prod_01KSB3XGHP9V2C0JTJQDTTNJF9'),
    recommend = 'prod_01KSB3XMEQC5CHTE8C7QWBR61N',
    status = 'active',
    bonus = 300,
    script = 'Предложите Цетрин вместо Супрастина: современный антигистаминный препарат с удобным приёмом 1 раз в день.',
    advantages = '["Удобный приём 1 раз в день","Современный антигистаминный выбор","Бонус фармацевту за замену"]'::jsonb,
    card = '{"partnerLabel":"Dr.Reddy`s","comparison":[{"label":"Категория","triggerValue":"антигистаминный препарат","recommendValue":"антигистаминный препарат","recommendHighlight":false},{"label":"Приём","triggerValue":"несколько раз в день","recommendValue":"1 раз в день","recommendHighlight":true}]}'::jsonb,
    updated_at = now()
WHERE id = 'r_s_1102c36c';

UPDATE rules
SET trigger = jsonb_build_object('kind','product','value','prod_01KSB3YPAKKF791WS1KS9XRY5C'),
    recommend = 'prod_01KSB3XMEQC5CHTE8C7QWBR61N',
    status = 'active',
    bonus = 300,
    script = 'Если в чеке уже есть Фенистил, предложите добавить Цетрин для системной антигистаминной поддержки.',
    advantages = '["Фенистил остаётся товаром в чеке","Цетрин добавляется к покупке","Правильный кросс-селл: продаём продвигаемый товар"]'::jsonb,
    card = '{"partnerLabel":"Dr.Reddy`s","comparison":[]}'::jsonb,
    updated_at = now()
WHERE id = 'r_x_63818c24';

-- Campaign: Zincit is the promoted cross-sell product for a cart that already contains Likopid.
UPDATE promos
SET title = 'Цинкит 10мг — кросс-селл к Ликопиду',
    status = 'active',
    medusa_product_id = 'prod_01KSB3XDZXAQDHZKY7XJ1E40HY',
    product_name = 'Цинкит 10мг шип таб №20',
    brand = 'Цинкит',
    barcode = '4030674012036',
    ipart_id = NULL,
    tiers = '[{"minQty":1,"price":2500,"bonus":300}]'::jsonb,
    updated_at = now()
WHERE id = 'pr_26909b11';

UPDATE rules
SET trigger = jsonb_build_object('kind','product','value','prod_01KSB3XJ36WF39ZNR3HE25RW02'),
    recommend = 'prod_01KSB3XDZXAQDHZKY7XJ1E40HY',
    status = 'active',
    bonus = 300,
    script = 'К Ликопиду предложите Цинкит: цинк дополняет иммунную поддержку и хорошо подходит как допродажа.',
    advantages = '["Цинк для иммунной поддержки","Шипучие таблетки","Допродажа без замены товара в чеке"]'::jsonb,
    card = '{"partnerLabel":"Цинкит","comparison":[]}'::jsonb,
    updated_at = now()
WHERE id = 'r_x_efc80fa1';

-- Campaign: Gedelix is the promoted replacement for Gastrosidin.
UPDATE promos
SET title = 'Геделикс капли 50мл — замена Гастросидина',
    status = 'active',
    medusa_product_id = 'prod_01KSB3YMDGEPJHM2DAJW5THGEY',
    product_name = 'Геделикс капли 50мл',
    brand = 'Krewel Meuselbach GmbH',
    barcode = '4030031893384',
    tiers = '[{"minQty":1,"price":0,"bonus":497}]'::jsonb,
    updated_at = now()
WHERE id = 'pr_f4b2b5b6';

UPDATE rules
SET trigger = jsonb_build_object('kind','product','value','prod_01KSB3YMDGZVVCC70NYRPH9Y53'),
    recommend = 'prod_01KSB3YMDGEPJHM2DAJW5THGEY',
    status = 'active',
    bonus = 497,
    script = 'Предложите Геделикс как понятную замену: растительные капли от кашля, удобный формат 50 мл.',
    advantages = '["Растительный препарат","Капли 50 мл","Повышенный бонус за замену"]'::jsonb,
    card = '{"partnerLabel":"Krewel Meuselbach","comparison":[{"label":"Формат","triggerValue":"таблетки","recommendValue":"капли 50 мл","recommendHighlight":true}]}'::jsonb,
    updated_at = now()
WHERE id = 'r_s_e2d712bf';

-- Campaign: Immunal is the promoted replacement for Likopid.
UPDATE promos
SET title = 'Иммунал — замена Ликопида',
    status = 'active',
    medusa_product_id = 'prod_01KSB3XK1JQKCCNE3KHKYHDYZW',
    product_name = 'Иммунал 50мл кап фл',
    brand = 'Sandoz',
    barcode = '3838957903832',
    tiers = '[{"minQty":1,"price":2890,"bonus":400}]'::jsonb,
    updated_at = now()
WHERE id = 'pr_6e2f0013';

UPDATE rules
SET trigger = jsonb_build_object('kind','product','value','prod_01KSB3XJ36WF39ZNR3HE25RW02'),
    recommend = 'prod_01KSB3XK1JQKCCNE3KHKYHDYZW',
    status = 'active',
    bonus = 400,
    updated_at = now()
WHERE id = 'r_s_707faabe';

-- Legacy manual POSM rule is not campaign-backed; archive it to avoid unexpected
-- non-campaign recommendations during cashier tests.
UPDATE rules
SET status = 'archived', updated_at = now()
WHERE id = 'r_x_8e32c07a' AND promo_id IS NULL;

-- Effective status follows campaign status and pair toggle.
UPDATE rules r
SET status = CASE
        WHEN p.status = 'active' AND COALESCE((r.card->>'pairActive')::boolean, true) THEN 'active'
        ELSE 'draft'
    END,
    updated_at = now()
FROM promos p
WHERE r.promo_id = p.id AND r.status <> 'archived';

COMMIT;
