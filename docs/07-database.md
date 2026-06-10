# Схема базы данных

PostgreSQL 16, миграции — **Flyway** (`admin-panel/backend/src/main/resources/db/migration/`),
применяются автоматически при старте бэкенда. На текущий момент — **21 миграция** (V001–V021).

## Миграции

| #    | Файл                   | Что создаёт / меняет                                                                            |
| ---- | ---------------------- | ----------------------------------------------------------------------------------------------- |
| V001 | `init`                 | Пустой маркер (bootstrap Flyway)                                                                |
| V002 | `auth`                 | `admin_users` (email, password_hash, role, status) + `refresh_tokens`                           |
| V003 | `catalog`              | `products` (id-slug, brand, vendor, mnn для матчинга правил)                                    |
| V004 | `rules`                | Rules Engine: `rules` (trigger JSONB, recommend FK→products, bonus, ab_test, метрики)           |
| V005 | `promo`                | `promos` (title, status, brand, period, budget, spent, kpi)                                     |
| V006 | `pharmacies`           | `chains` + `pharmacies` (chain_id FK, group pilot/control/rolled, метрики)                      |
| V007 | `pharmacists`          | `pharmacists` (iin uniq, phone uniq, tier, status, balance, courses_done)                       |
| V008 | `payouts`              | `payout_batches` (period, status, reviewer_id) + `payout_items` (amount, flag)                  |
| V009 | `lms`                  | `courses` (title, status, lessons, duration_min, enrolled, completed, bonus)                    |
| V010 | `screens`              | `playlists` (status, slides_count, duration_sec) + `slides` (kind video/image, media_url→MinIO) |
| V011 | `ai_exam`              | `exam_questions` (prompt, kind, keywords JSONB, difficulty 1–5)                                 |
| V012 | `receipts`             | `pending_bonuses` + `receipts` (photo*url, fiscal_id, parsed*\*, status, bonus_credited)        |
| V013 | `posm_recommendations` | `product_pos_codes` (pos_code→product) + `recommendation_events` (session, outcome)             |
| V014 | `posm_sales_excel`     | `pos_sales` (лог кассы) + `excel_imports`/`excel_sale_rows`; колонки источников в `receipts`    |
| V015 | `cdp_profiles`         | `cdp_profiles` (phone uniq, name, tier, registered_by_pharmacist/at_pharmacy)                   |
| V016 | `playlist_target`      | `playlists.pharmacy_id` (NULL=глобальный, иначе — на конкретную аптеку)                         |
| V017 | `app_releases`         | `app_releases` (platform, version, url, sha256, mandatory, is_current) — авто-апдейт POSM       |
| V018 | `rule_card`            | `products.volume` + `rules.card` JSONB (строки сравнения, партнёр, цель)                        |
| V019 | `mobile_auth`          | `pharmacy_id/name` nullable (self-register); `mobile_otps` + `mobile_refresh_tokens`            |
| V020 | `drop_ocr_score`       | Удаляет `receipts.ocr_score` (OCR отказались)                                                   |
| V021 | `drop_qr_raw`          | Удаляет `receipts.qr_raw` (QR/ОФД не используется)                                              |

## Таблицы по доменам

**Аутентификация**

- `admin_users` — HQ-менеджеры (bcrypt-пароль, роль, статус)
- `refresh_tokens` — refresh админки (хэш)
- `mobile_otps` — OTP фармацевтов (phone PK, code_hash, TTL/попытки)
- `mobile_refresh_tokens` — refresh мобилки (отдельно от админских)

**Каталог и правила**

- `products` — товары (slug-id, brand, vendor, mnn, volume)
- `rules` — правила замены/допродажи (`trigger` JSONB, `recommend`, `bonus`, `card` JSONB, метрики)
- `product_pos_codes` — соответствие кода кассы товару

**Аптеки и фармацевты**

- `chains` / `pharmacies` (группа pilot/control/rolled для A/B)
- `pharmacists` (iin, phone, tier Silver/Gold/Platinum, balance, courses_done)

**Чеки и бонусы**

- `pending_bonuses` — отложенные бонусы (созданы на кассе, ждут подтверждения)
- `receipts` — чеки (photo_url→MinIO, status pending/flagged/approved/rejected, источники
  подтверждения: лог/Excel/ручная, bonus_credited)
- `pos_sales` — лог продаж с кассы (источник №1 сверки)
- `excel_imports` / `excel_sale_rows` — Excel-выгрузка «Стандарт-Н» (источник №2)
- `recommendation_events` — показы рекомендаций и их исходы

**Выплаты**

- `payout_batches` (период, статус, согласующий) / `payout_items` (сумма, флаг)

**Контент и обучение**

- `playlists` / `slides` — DOOH-плейлисты (per-pharmacy targeting через `playlists.pharmacy_id`)
- `courses` — курсы LMS
- `exam_questions` — банк вопросов AI-экзамена

**Лояльность и релизы**

- `cdp_profiles` — клиенты программы лояльности (по телефону)
- `app_releases` — манифест версий POSM-клиента (sha256, mandatory, is_current)

## Заметные архитектурные решения

- **JSONB для правил** (`rules.trigger`, `rules.card`) — гибкая форма триггеров (product/mnn) и
  богатой карточки-сравнения без раздувания схемы.
- **Раздельные таблицы refresh** для админа и фармацевта — разные модели сессий и безопасности.
- **Три источника подтверждения чека** (лог кассы + Excel + ручная модерация) вместо OCR/QR —
  колонки-флаги в `receipts` (`confirmed_by_log`, `confirmed_by_excel`); OCR/QR удалены (V020/V021).
- **`playlists.pharmacy_id` nullable** — один механизм и для глобальных плейлистов, и для адресных.
- **`pharmacy_id` фармацевта nullable** — поддержка само-регистрации (pending) из мобилки до
  привязки к аптеке.

## Сидинг

- **dev** (`DevDataSeeder`, профиль `dev`): 3 админа, продукты, правила, сети/аптеки, курсы и т.д.
- **prod** (`ProdBootstrap`, профиль `prod`): только первый админ из `ADMIN_BOOTSTRAP_*`; реальные
  аптеки (~523) подгружаются из Medusa (`RealPharmacySeeder`).
