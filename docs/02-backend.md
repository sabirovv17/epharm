# Бэкенд (Kotlin + Spring Boot)

**Путь:** `admin-panel/backend/` · **Стек:** Kotlin 2.0.21 (JVM 22), Spring Boot 3.3.5
(Web, Security, Data JPA, Validation, Actuator), Gradle Kotlin DSL.

Единый монолит. Внутренний домен пакетов — `kz.epharm.*`. Все клиенты ходят через Caddy на
`backend:8080` (наружу порт не публикуется).

## Ключевые зависимости

| Библиотека              | Версия  | Зачем                                     |
| ----------------------- | ------- | ----------------------------------------- |
| JJWT                    | 0.12.6  | подпись/проверка JWT (HMAC-256)           |
| Flyway                  | 10.20.1 | миграции БД (25 шт.)                      |
| PostgreSQL driver       | —       | основная БД                               |
| AWS SDK S3              | 2.29.9  | загрузка медиа в MinIO/S3 (+ presigner)   |
| Apache POI              | 5.3.0   | парсинг Excel-выгрузки «Стандарт-Н»       |
| Spring Data JPA / Redis | 3.3.5   | ORM (Hibernate) / кэш                     |
| SpringDoc OpenAPI       | 2.6.0   | Swagger UI                                |
| Testcontainers          | 1.20.3  | интеграционные тесты на реальном Postgres |
| MockK                   | 1.13.13 | моки в тестах                             |

## Доменные пакеты

`src/main/kotlin/kz/epharm/`

| Пакет         | Ответственность                                                                                                     |
| ------------- | ------------------------------------------------------------------------------------------------------------------- |
| `shared`      | `SecurityConfig`, JWT-фильтр, обработка ошибок, утилиты (Luhn, ИИН, телефон), `ProdBootstrap`, абстракция хранилища |
| `auth`        | Аутентификация HQ-консоли (email/пароль + JWT + refresh + роли)                                                     |
| `mobile`      | API мобилки фармацевта (auth/OTP, catalog, receipts, pharmacies, profile)                                           |
| `catalog`     | Мастер-данные товаров (бренды, МНН, CRUD продуктов)                                                                 |
| `rules`       | Rules Engine: правила замены/допродажи, JSONB-триггеры, A/B                                                         |
| `promo`       | Маркетинговые кампании (бюджеты, периоды, KPI)                                                                      |
| `pharmacies`  | Сети и аптеки (группы pilot/control/rolled для A/B)                                                                 |
| `pharmacists` | Реестр фармацевтов (тир, баланс, прогресс курсов, блокировка)                                                       |
| `finance`     | Выплаты (батчи, согласование, роль FINANCE_REVIEWER)                                                                |
| `receipts`    | Сверка чеков (источники: лог + Excel + ручная модерация, анти-фрод, начисление)                                     |
| `screens`     | Indoor-DOOH плейлисты (видео/картинки, загрузка в MinIO)                                                            |
| `lms`         | Курсы обучения фармацевтов                                                                                          |
| `ai_exam`     | Банк вопросов для пост-курсового экзамена                                                                           |
| `posm`        | Интеграция с кассой (рекомендации, исходы, продажи, CDP)                                                            |
| `medusa`      | Клиент Medusa Store API (реальный каталог, кэш, прокси)                                                             |
| `cdp`         | Профили лояльности клиентов (по телефону)                                                                           |
| `dashboard`   | Сводная аналитика (KPI)                                                                                             |
| `lift`        | A/B-аналитика (pilot vs control)                                                                                    |
| `appupdate`   | Манифест авто-апдейта POSM-клиента (версии, SHA256)                                                                 |

## Модель безопасности

`shared/SecurityConfig.kt` — stateless, JWT-фильтр кладёт `Authentication` в `SecurityContext`.

| URL-паттерн                                                  | Доступ                                                               |
| ------------------------------------------------------------ | -------------------------------------------------------------------- |
| `/api/admin/**`                                              | роли `HQ_HEAD`, `CATEGORY_LEAD`, `BRAND_MANAGER`, `FINANCE_REVIEWER` |
| `/api/mobile/**`                                             | роль `PHARMACIST`                                                    |
| `/api/posm/**`                                               | device-key (`X-Posm-Key`), без JWT                                   |
| `/actuator/**`                                               | роли admin                                                           |
| `/api/admin/auth/{login,refresh}`                            | permitAll                                                            |
| `/api/mobile/auth/{sms/request,sms/verify,register,refresh}` | permitAll                                                            |
| `/api/health`, `/actuator/health`, `/swagger-ui/**`          | permitAll                                                            |

**Роли админки:**

- `HQ_HEAD` — полный доступ
- `CATEGORY_LEAD` — создание/редактирование правил и промо
- `BRAND_MANAGER` — чтение правил/промо
- `FINANCE_REVIEWER` — согласование выплат

**Метод-уровневая защита** (`@EnableMethodSecurity` + `@PreAuthorize`):

- `PayoutController.approve()` и `.generate()` → `hasAnyRole('FINANCE_REVIEWER','HQ_HEAD')`

## Справочник REST API

### Admin API (`/api/admin/**`, JWT + роли)

**Auth** — `auth/controller/AdminAuthController.kt` (`/api/admin/auth`)

- `POST /login` · `POST /refresh` · `POST /logout` · `GET /me`

**Каталог** — `CatalogController` (`/api/admin/catalog`)

- `GET|POST /products` · `GET|PATCH|DELETE /products/{id}` · `GET /brands` · `GET /mnn-groups`

**Rules Engine** — `RuleController` (`/api/admin/rules`)

- `GET ?type=&status=` · `GET /{id}` · `POST` · `PATCH /{id}` · `POST /{id}/archive` · `POST /{id}/duplicate`

**Промо** — `PromoController` (`/api/admin/promo`)

- `GET ?status=` · `GET /{id}` · `POST` · `PATCH /{id}` · `POST /{id}/archive` · `POST /{id}/restore`
- `GET /{id}/rules` · `PUT /{id}/rules` — правила замены/кросс-селла из кампании (T2): генерит/читает
  substitution+crosssell-правила (`rules.promo_id`), апсертит товары витрины в локальный каталог.
  Кампания = 1 товар; цена read-only из Medusa; `pharmacistBonus` — бонус фармацевту; `override_image/description`.

**Аптеки и сети** — `PharmacyController` (`/api/admin/pharmacies`)

- `GET|POST /chains` · `PATCH|DELETE /chains/{id}` · `GET ?group=&chainId=` · `GET /{id}` · `POST` · `PATCH|DELETE /{id}`

**Фармацевты** — `PharmacistController` (`/api/admin/pharmacists`)

- `GET ?status=&pharmacyId=` · `GET /{id}` · `POST` · `PATCH /{id}` · `POST /{id}/block` · `POST /{id}/unblock`

**Финансы/выплаты** — `PayoutController` (`/api/admin/payouts`) — _@PreAuthorize_

- `GET ?status=` · `GET /{id}` · `GET /{id}/items` · `POST /{id}/approve` · `POST /generate?period=`

**Дашборд/аналитика** — `DashboardController` (`/api/admin/dashboard/summary`), `LiftController` (`/api/admin/lift`)

**LMS** — `LmsController` (`/api/admin/lms/courses`)

- `GET ?status=` · `GET /{id}` · `POST` · `PATCH /{id}` · `DELETE /{id}`

**Экраны** — `ScreenController` (`/api/admin/screens`)

- `GET|POST /playlists` · `PATCH|DELETE /playlists/{id}` · `GET /slides` · `POST /slides` (multipart) · `DELETE /slides/{id}` · `POST /slides/{id}/assign`
- `GET /connected` — сколько касс сейчас онлайн (T4): `{total, devices[]}` по пульсам heartbeat

**AI-Exam** — `AiExamController` (`/api/admin/ai-exam/questions`)

- `GET ?kind=` · `POST` · `PATCH /{id}` · `DELETE /{id}`

**Сверка чеков** — `ReconcileController` (`/api/admin/reconcile`)

- `GET ?status=` · `GET /summary` · `GET /{id}` · `POST /{id}/approve` · `POST /{id}/reject` · `POST /import-excel` (multipart)

**Релизы POSM** — `AppReleaseController` (`/api/admin/app-releases`) — `GET` · `POST`

**Витрина (Medusa)** — `AdminStorefrontController` (`/api/admin/storefront`) — `GET /products?q=&limit=&offset=` · `GET /products/{id}`

### Mobile API (`/api/mobile/**`, JWT PHARMACIST)

**Auth** — `MobileAuthController` (`/api/mobile/auth`)

- `POST /sms/request` · `POST /sms/verify` · `POST /register` · `POST /refresh` (все public) · `POST /logout` · `GET /me`

**Каталог** — `MobileCatalogController` (`/api/mobile/catalog`) — `GET /products?q=&category=&limit=&offset=` · `GET /products/{id}` · `GET /categories`

**Аптеки** — `MobilePharmacyController` (`/api/mobile/pharmacies`) — `GET ?q=&city=` (адреса для привязки чека)

**Чеки** — `MobileReceiptController` (`/api/mobile/receipts`) — `POST` (multipart, фото) · `GET` (история)

**Профиль** — `MobileProfileController` (`/api/mobile/me`) — `GET` (баланс, тир, прогресс)

### POSM API (`/api/posm/**`, X-Posm-Key)

`PosmController`:

- `POST /recommend` — рекомендации по корзине
- `POST /recommendations/{eventId}/outcome` — исход (accepted/rejected)
- `POST /sales` — лог завершённой продажи
- `GET /playlists/active?pharmacyId=` — активный плейлист экрана
- `POST /heartbeat?deviceId=&pharmacyId=` — пульс кассы (T4, каждые ~60с) для счётчика подключений
- `GET /app/version?platform=` — текущая версия для авто-апдейта
- `POST /cdp/lookup` · `POST /cdp/register` — лояльность по телефону

### Прочее

- `GET /api/health` — liveness (`shared/HealthController.kt`)

## Ключевые сервисы

| Сервис                                              | Назначение                                                                                |
| --------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `JwtService`                                        | подпись/валидация JWT (access + refresh)                                                  |
| `RefreshTokenService` / `MobileRefreshTokenService` | ротация refresh (SHA-256 в БД), отдельно для admin и фармацевтов                          |
| `OtpService`                                        | генерация/проверка OTP (`mobile_otps`, TTL + throttling). Dev-режим → `544544`            |
| `MedusaClient` + `MedusaCatalogCache`               | REST к Medusa, кэш каталога в памяти                                                      |
| `MedusaPriceService`                                | резолвер цены товара из Medusa БЕЗ кэша (для создания промо + ежедневного рефреша)        |
| `PromoPriceScheduler`                               | `@Scheduled` ежедневный рефреш цен promos.tiers + products.price из Medusa (T1)           |
| `PromoRulesService`                                 | генерация правил замены/кросс-селла из кампании, апсерт товаров витрины (T2)              |
| `DevicePresenceService`                             | счётчик подключённых касс (in-memory heartbeat + TTL, T4)                                 |
| `S3MediaStorage` (impl `MediaStorage`)              | загрузка фото/слайдов в MinIO (AWS SDK v2)                                                |
| `ReconcileService`                                  | сверка чеков (лог + Excel + ручная) и начисление бонуса                                   |
| `ExcelImportService`                                | парсинг Excel «Стандарт-Н», матчинг pending-бонусов                                       |
| `RulesEngineService`                                | подбор правила по корзине (JSONB-триггеры)                                                |
| `RecommendationService`                             | запись событий рекомендаций, создание pending-бонусов                                     |
| `PayoutService`                                     | генерация батчей выплат, workflow согласования                                            |
| `ScreenService`                                     | CRUD плейлистов/слайдов, загрузка медиа, пересчёт                                         |
| `DevDataSeeder`                                     | dev-данные (профиль `dev`)                                                                |
| `ProdBootstrap`                                     | прод-инициализация: fail-fast на дефолтных секретах, первый админ из env (профиль `prod`) |
| `RealPharmacySeeder`                                | загрузка ~523 реальных аптек из Medusa                                                    |

## Сборка и запуск

```bash
# из корня репозитория
docker compose up -d                      # Postgres + Redis + MinIO (dev)
cd admin-panel/backend
./gradlew bootRun                         # профиль dev по умолчанию
curl localhost:8080/api/health            # → {"status":"ok"}
```

- **Dockerfile** — multi-stage (gradle build → runtime), expose 8080.
- Миграции применяются автоматически при старте (Flyway).
- Полная схема БД — в [`07-database.md`](07-database.md).
