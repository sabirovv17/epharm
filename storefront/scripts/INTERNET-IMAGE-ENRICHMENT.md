# Дозаполнение отсутствующих изображений товаров

Скрипт `enrich-missing-product-images.mjs` работает только с товарами без изображения.
Существующие `thumbnail_url` и галерея никогда не перезаписываются. Основной ключ
сопоставления — точный GTIN/штрихкод; поиск только по похожему названию запрещён.

## 1. Инвентаризация

```powershell
node scripts/enrich-missing-product-images.mjs inventory `
  --catalog-url "https://apteka-demo.quasar-it.kz/api/catalog" `
  --manifest ".data/image-enrichment/missing-products.jsonl"
```

## 2. Открытые источники

Для массовой обработки используйте выгрузки, а не тысячи запросов к API:

- Open Beauty Facts: `https://world.openbeautyfacts.org/data/en.openbeautyfacts.org.products.csv.gz`
- Open Products Facts: `https://world.openproductsfacts.org/data/en.openproductsfacts.org.products.csv.gz`
- Open Food Facts image keys: `https://openfoodfacts-images.s3.eu-west-3.amazonaws.com/data/data_keys.gz`

```powershell
node scripts/enrich-missing-product-images.mjs discover `
  --inventory ".data/image-enrichment/missing-products.jsonl" `
  --open-beauty-csv ".data/image-enrichment/openbeautyfacts.csv.gz" `
  --open-products-csv ".data/image-enrichment/openproductsfacts.csv.gz" `
  --open-food-keys ".data/image-enrichment/openfoodfacts-data-keys.gz" `
  --manifest ".data/image-enrichment/candidates.jsonl"
```

Строки `review_image_role` (например, задняя этикетка или случайный ракурс)
автоматически не применяются. Низкое сходство названия сохраняется как предупреждение,
но точный уникальный GTIN и выбранное фронтальное фото остаются основным правилом. Для лекарственных
средств приоритетным источником остаётся Национальный каталог товаров Казахстана
(НКТ): его API возвращает `good_img`/`good_images` по GTIN, но требует корпоративный
API-ключ. Ключ хранится только в секретах сервера.

Перед записью проверьте ограниченную выборку декодированием и конвертацией:

```powershell
node scripts/enrich-missing-product-images.mjs verify `
  --candidates ".data/image-enrichment/candidates.jsonl" `
  --output-dir ".data/image-enrichment/canary" `
  --manifest ".data/image-enrichment/canary.jsonl" `
  --limit 20
```

## 3. Canary и применение

Сначала сделайте резервную копию PostgreSQL и выполните dry-run:

```bash
node scripts/enrich-missing-product-images.mjs apply \
  --candidates /srv/inkar-shop/manifests/image-candidates.jsonl \
  --uploads-dir /srv/inkar-shop/data/uploads/catalog \
  --manifest /srv/inkar-shop/manifests/image-apply-dry-run.jsonl \
  --limit 5
```

После визуальной проверки пяти карточек:

```bash
DATABASE_URL="$DATABASE_URL" node scripts/enrich-missing-product-images.mjs apply \
  --candidates /srv/inkar-shop/manifests/image-candidates.jsonl \
  --uploads-dir /srv/inkar-shop/data/uploads/catalog \
  --manifest /srv/inkar-shop/manifests/image-apply.jsonl \
  --limit 5 --apply
```

Полный запуск требует `--confirm-all`. Каждая запись хранит источник, исходный URL,
штрихкод и сведения о лицензии для аудита и атрибуции.
