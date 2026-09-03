# Локальный каталог Inkar

`db/migrations/001_catalog_core.sql` создаёт независимый read-model лекарственных
товаров: продукты, варианты/SKU, категории, изображения, аптеки, остатки/цены и
transactional outbox для ACC/Epharm/CDP.

Medusa пока остаётся источником истины. Синхронизация идёт по стабильным Medusa ID,
SKU и `metadata.ware_id`; названия и изображения не используются как ключи.

## Первичная настройка

1. Создать отдельную базу Postgres и сохранить `DATABASE_URL` только в серверном
   `.env.production`.
2. Применить версионированную схему:

   ```bash
   npm run db:migrate
   ```

3. Проверить весь источник без записи:

   ```bash
   npm run catalog:sync -- --full
   ```

4. Сделать небольшой canary и сверить товары/SKU/изображения:

   ```bash
   npm run catalog:sync -- --apply --full --limit 100
   ```

5. Выполнить полный повторяемый импорт:

   ```bash
   npm run catalog:sync -- --apply --full --mark-missing-inactive
   ```

6. Проверить `catalog_product_read_model`, затем включить локальный read-path:

   ```env
   CATALOG_READ_SOURCE=postgres
   ```

   При недоступности Postgres `/api/catalog` автоматически читает Medusa и помечает
   ответ заголовками `x-catalog-source: medusa_fallback` и `x-data-state: degraded`.

Только после полного успешного прогона разрешено пометить исчезнувшие в Medusa
товары неактивными:

```bash
npm run catalog:sync -- --apply --full --mark-missing-inactive
```

Скрипт ничего не удаляет физически. Каждый запуск записывается в
`catalog_import_runs`, страницы применяются транзакционно, повторный запуск делает
upsert. Изменённая уже применённая миграция блокируется по SHA-256 — для правок
нужно добавлять следующий SQL-файл.

Docker-образ применяет миграции перед стартом Next.js и не запускает сайт при
неуспешной миграции. Для периодического обновления полного каталога на VPS:

```bash
cd /var/www/inkar-shop
docker compose --env-file .env.production exec -T web node scripts/sync-medusa-catalog.mjs --apply
```

Команду можно поставить в systemd timer/cron раз в час. Скрипт держит advisory lock
на весь импорт и безопасно отклоняет второй одновременный запуск; `flock` в
расписании можно оставить как дополнительную защиту.

## Изображения

`scripts/import-sku-images.mjs` загружает исходные файлы в Medusa только при строгом
совпадении UUID файла, `metadata.ware_id` и уникального SKU. После импорта обычная
синхронизация копирует URL и metadata изображений в локальную базу.

## Следующий контракт ACC/Epharm

Внешняя система должна передавать канонический `product_id`/`variant_id`, аптеку,
цену, остаток, время изменения и idempotency key. Нормализованные значения попадают
в `catalog_pharmacy_offers`; подтверждённые заказы отправляются через
`integration_outbox`, чтобы сетевой сбой не терял заказ.
