# Epharm — План разработки (Admin Console + Backend + POSM)

> **Источники:** `ИТОГОВОЕ_ТЗ.pdf` (Module 1 §3, Module 2 §4, общая архитектура §2, roadmap §7), `admin-panel/design-tokens-admin.md`, `admin-panel/references/*` (JSX-эталон ~3 300 строк, 12 секций), `claude-notes.md` (статус мобильного приложения, моки данных).

## Контекст

В проекте уже готовы:

- **Мобильное приложение фармацевта** (Flutter, iOS/Android) — golden path работает на mock-репозиториях. Auth по телефону + OTP, Home c промо-каталогом, recipe-flow (camera → review → success), профиль и под-страницы. Бэкенда нет, всё in-memory.
- **Дизайн админ-консоли** — полный токенизированный референс в `admin-panel/design-tokens-admin.md` + JSX-эталон в `admin-panel/references/` (Sidebar + Topbar + 12 секций + CommandPalette + Toast + Modal/Drawer + Sparkline/Heatmap + fixtures на `window.AD`). Эталон сейчас рендерится через `<script type="text/babel">` — не production-ready, но визуально и логически полный.

Чего нет:

- Production-сборки React-админки (нужно перенести JSX-эталон в Vite + TS + Tailwind с реальными модулями вместо `Object.assign(window, …)`).
- Backend сервиса — нет ни одного API endpoint'а; мобилка и админка работают на статичных моках.
- **Module 2 (POSM в Стандарт-Н)** — нет ничего: ни backend-эндпоинтов Rules Engine, ни Electron-sidecar для подсказок на кассе, ни CDP-формы ввода телефона клиента.

## Цель плана

1. Поднять production админ-панель (React + Vite + TS + Tailwind), сохранив 1:1 визуал и UX эталона.
2. Написать Kotlin + Spring Boot backend (один сервис на старте), который покрывает мобильное приложение и админку через REST. PostgreSQL + Flyway + JWT.
3. Реализовать **Module 2 (POSM в Стандарт-Н)** — backend Rules Engine API + Electron-sidecar клиент, который слушает события Стандарт-Н и показывает рекомендации замен/cross-sell на втором мониторе POS-моноблока.
4. Снять моки в мобильном приложении и переключить на реальный API.

Порядок: **фронт-админка с моками → Spring backend → подключение фронта к API → мобилка на API → POSM-модуль**. Так раньше всего получаем визуальный демо-материал для стейкхолдеров, потом обвешиваем его реальной логикой.

## Стек

| Слой               | Технологии                                                                                                                                                        | Обоснование                                                                                                                                                                          |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Admin frontend     | React 18 + Vite + TypeScript + Tailwind 3 + React Router + TanStack Query + Zustand (легкий global state) + lucide-react (иконки)                                 | Эталон уже на Tailwind + JSX → перенос без переписывания дизайна. TanStack Query закрывает кеш+рефетч+optimistic, Zustand — sidebar collapsed / current role / command palette open. |
| Admin testing      | Vitest + Testing Library + Playwright (smoke)                                                                                                                     | Достаточно для админ-UI; e2e только на критических flow (правила, сверка чеков, выплаты).                                                                                            |
| API mocks (фаза 1) | MSW (Mock Service Worker)                                                                                                                                         | Один и тот же fetch-код работает и с моками, и с реальным API. Снимаем моки переключением флага.                                                                                     |
| Backend            | Kotlin 1.9 + Spring Boot 3.3 (Web, Security, Data JPA, Validation) + Gradle Kotlin DSL                                                                            | Стандарт для Spring Kotlin.                                                                                                                                                          |
| Persistence        | PostgreSQL 16 + Flyway + Hibernate/JPA + jOOQ для отчётов (опционально, если SQL станет тяжёлым)                                                                  | PostgreSQL — указан в ТЗ §2. Flyway — миграции с первого коммита.                                                                                                                    |
| Auth               | Spring Security + JWT (access 15 мин + refresh 30 дней) + bcrypt для админов, OTP-через-SMS для фармацевтов                                                       | OTP-flow уже есть в мобилке (mock `544544`) — нужно сохранить контракт.                                                                                                              |
| Files / receipts   | S3-совместимое хранилище (MinIO в dev, Yandex Object Storage / AWS S3 в prod)                                                                                     | Для фото чеков и медиа indoor-экранов (ТЗ §2).                                                                                                                                       |
| Async / queues     | Spring `@Async` + Redis (через Spring Data Redis) на старте; BullMQ упоминается в ТЗ §5 как опция для маркетплейсов, но к нашему scope не относится.              | Минимум зависимостей до первой нагрузки.                                                                                                                                             |
| OCR (recipe-flow)  | Заглушка-сервис с интерфейсом `OcrService` → в фазе 1 возвращает фейковые поля; в фазе 2 — Tesseract via Docker или внешний (Yandex Vision / Google Document AI). | Не блокирует MVP админки.                                                                                                                                                            |
| POSM sidecar       | Electron 30 + React (тот же стек что админка) + локальный IPC к Стандарт-Н (вариант 2 из ТЗ §4 «Sidecar-приложение»). WebSocket к backend для Rules Engine API.   | ТЗ рекомендует sidecar как MVP, plugin/extension — позже.                                                                                                                            |
| CI                 | GitHub Actions (или GitLab CI — уточнить у пользователя): lint + test + build для фронта; gradle build + tests для бэка.                                          | Один раз настроить — экономит часы.                                                                                                                                                  |
| Деплой             | Docker-compose в dev, Kubernetes (или Nomad) в prod — но это вне scope ближайших этапов.                                                                          | Достаточно один раз обсудить в Этапе 7.                                                                                                                                              |

## Структура репозитория

Сейчас `PharmaPayV2/` содержит мобильное приложение (Flutter) + папку `admin-panel/` с эталоном. Предлагаемая раскладка:

```
PharmaPayV2/
├── lib/                          # Flutter mobile app (как есть)
├── admin-panel/
│   ├── design-tokens-admin.md    # design system (как есть)
│   ├── references/               # JSX-эталон (как есть, source-of-truth для UX)
│   ├── PLAN.md                   # этот документ
│   └── web/                      # NEW — production React admin
│       ├── src/
│       │   ├── app/              # роутер, провайдеры, App.tsx
│       │   ├── layout/           # Sidebar, Topbar, CommandPalette, RoleSwitcher, ContractModal
│       │   ├── ui/               # Button, Input, Select, Toggle, Tabs, Chip, Modal, Drawer, Toast, Metric, Sparkline, ProgressBar, Empty
│       │   ├── lib/              # api client, query keys, formatters, hooks
│       │   ├── features/
│       │   │   ├── dashboard/    # routes + components per section
│       │   │   ├── rules/
│       │   │   ├── promo/
│       │   │   ├── screens/
│       │   │   ├── pharmacies/
│       │   │   ├── pharmacists/
│       │   │   ├── reconcile/
│       │   │   ├── ai-exam/
│       │   │   ├── finance/
│       │   │   ├── lift/
│       │   │   ├── lms/
│       │   │   └── settings/
│       │   ├── mocks/            # MSW handlers + fixtures (изначально копия data.jsx)
│       │   └── types/            # shared API types (zod схемы)
│       ├── index.html
│       ├── vite.config.ts
│       ├── tailwind.config.ts
│       ├── tsconfig.json
│       └── package.json
├── backend/                      # NEW — Kotlin + Spring Boot monolith
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/
│       └── main/kotlin/kz/epharm/
│           ├── EpharmApplication.kt
│           ├── auth/              # SMS-OTP, JWT, roles (pharmacist, hq-admin, brand-manager)
│           ├── catalog/           # products, brands, MNN groups, PIM-lite
│           ├── promo/             # campaigns, bonuses
│           ├── rules/             # substitution + crosssell rules engine
│           ├── receipts/          # upload, OCR, reconcile queue, status flow
│           ├── pharmacies/        # network, chains, groups (pilot/control/rolled)
│           ├── pharmacists/       # registry, balances, tiers
│           ├── payouts/           # batches, items, approval
│           ├── lms/               # courses, lessons, progress
│           ├── screens/           # DOOH playlists, slides, schedules
│           ├── ai_exam/           # bank, sessions, results, certificates
│           ├── posm/              # Module 2 — recommendations, replacements, CDP lookup
│           └── shared/            # common, errors, pagination, audit
│       └── main/resources/
│           ├── application.yml
│           └── db/migration/      # Flyway: V001__init.sql, V002__rules.sql, …
│       └── test/                  # JUnit5 + Testcontainers (PostgreSQL)
└── posm-sidecar/                 # NEW — Electron client for Module 2 (Этап 5)
    ├── package.json
    └── src/
        ├── main/                  # Electron main process, IPC к Стандарт-Н, окно поверх
        └── renderer/               # React UI рекомендации замены (popup)
```

Альтернатива (если пользователь предпочитает) — три отдельных репозитория. По умолчанию идём через монорепо: общий тип-контракт между фронтом и бэком проще держать в одном дереве.

---

## Этапы

### Этап 0 — Bootstrap (1–2 дня)

**Цель:** запустить пустой Vite-проект, который рендерит «Hello Console», и пустой Spring Boot, который отдаёт `GET /api/health`.

Что делаем:

1. `admin-panel/web/` — `npm create vite@latest web -- --template react-ts`, добавить Tailwind 3 с конфигом из `design-tokens-admin.md` (палитра brand/ink/paper, JetBrains Mono + Manrope через `@fontsource`), React Router v6, TanStack Query, Zustand.
2. Перенести `tailwind.config.ts` с полной палитрой из design-tokens (`brand.green.50–800`, `ink.50–900`, `paper.*`, `accent.*`), shadow-токены, font-family.
3. `backend/` — `gradle init` (Kotlin DSL), Spring Boot 3.3, базовый `HealthController`, Flyway, PostgreSQL JDBC, тесты с Testcontainers.
4. Один `docker-compose.yml` в корне: `postgres`, `redis`, `minio`. Чтобы бэкендеру было что поднять локально.
5. Если CI ещё не настроен — добавить минимальный `.github/workflows/ci.yml` (lint + unit tests).

**Verify:** `npm run dev` рендерит canvas-фон `paper/DEFAULT`. `./gradlew bootRun` отвечает 200 OK на `GET /api/health`. `docker-compose up -d` поднимает PG + Redis + MinIO.

### Этап 1 — Admin Layout + UI-kit (3–5 дней)

**Цель:** перенести `layout.jsx` + `ui.jsx` + `icons.jsx` в `admin-panel/web/src/{layout,ui}/` как TS-компоненты. Все остальные секции пока показывают «In progress».

Переносим в строгом порядке:

1. **`ui/`** — Button (5 вариантов), IconButton, Input, Select, Toggle, Tabs, StatusChip, Avatar, Modal, Drawer, ToastHost (Zustand store), Metric/SummaryBar, SectionCard, ProgressBar, Sparkline (SVG), Empty, ComingSoonBanner. Все стили — Tailwind utility-классы из дизайн-токенов; никаких inline styles. **Icons** — переезжаем на `lucide-react` где совпадает, кастомные glyph'ы (Coin, ReceiptStamp) переносим как SVG-компоненты.
2. **`layout/Sidebar.tsx`** — collapsed/expanded состояния (260 / 72 px), active row (`brand-green-600` inset + gradient overlay), Contract widget внизу.
3. **`layout/Topbar.tsx`** — breadcrumb, fake-search opens CommandPalette, period selector, bell, role-pill.
4. **`layout/CommandPalette.tsx`** — `⌘K` / `Ctrl+K` listener в `useEffect`, фильтр по `SECTIONS` + top-N продуктов/аптек.
5. **`layout/RoleSwitcher.tsx`** + **`layout/ContractModal.tsx`** — модалки.
6. Router в `app/router.tsx` — `/dashboard`, `/rules`, `/promo`, … (12 маршрутов) с lazy-loaded компонентами секций (пока stub'ы).

**Verify:** проходим по 12 пунктам Sidebar — каждый рендерит SectionCard «Скоро»; CommandPalette открывается по `⌘K`, role-switcher работает.

### Этап 2 — 12 секций админки на моках (10–15 дней)

**Цель:** каждая страница из `references/sections/*.jsx` переезжает в `features/<section>/` как набор TS-компонентов. Данные — из MSW (handlers переводят `window.AD` в HTTP-ответы).

Приоритет (по сложности и весу для бизнеса):

1. **Rules Engine** (`features/rules/`, главный экран ТЗ §3.2, мокап Figure 32) — 667 строк JSX, самый детальный. Замены / Cross-sell / Архив + конструктор правила (триггер, рекомендация, скрипт, бонус, статус, A/B).
2. **Reconcile** (`features/reconcile/`) — очередь сверки чеков, 3-веточный flow (auto / manual / anti-fraud) из ТЗ §3.5.
3. **Pharmacies + Pharmacists** — список 500 аптек (CHAINS + PHARMACY_LIST в моках) и реестр 48 фармацевтов с балансами/tier.
4. **Finance** — батчи выплат, items, статусы approve/pending.
5. **Promo** — кампании со статусами active/draft/paused.
6. **Dashboard** — KPI tiles 4-up + sparkline + heatmap.
7. **Screens** — DOOH плейлисты + слайды.
8. **AI-Exam** — bank вопросов + результаты.
9. **LMS** — каталог курсов.
10. **Lift** — pilot vs control график.
11. **Settings** — заглушка с базовыми полями.

Каждая секция:

- Один файл `features/<section>/<Section>Page.tsx` + по необходимости вложенные `*Table.tsx`, `*Drawer.tsx`, `*RuleBuilder.tsx`.
- Хук `useFoo()` из TanStack Query → fetch к `/api/<section>` → MSW handler возвращает фикстуру.
- Состояние модалок/drawer'ов — локально через `useState`. Global state (role, sidebar collapsed) — Zustand.

**Verify:** в каждой секции работают фильтры/табы/драверы. Selected row подсвечивается. Toast'ы вылетают на действия (`approve`, `reject`, `save rule`). На холодном перезапуске данные на месте (MSW отдаёт).

### Этап 3 — Backend monolith + переключение фронта на реальные API (15–20 дней)

**Цель:** Spring Boot отдаёт реальные данные из PostgreSQL для всех 12 секций админки + auth-flow мобильного приложения.

Подэтапы:

3.1 **Схема БД (Flyway)** — миграции на каждый домен:

- `auth` — `users` (id, role, password_hash for admin), `pharmacists` (id, name, iin, phone, pharmacy_id, tier, balance, status), `refresh_tokens`.
- `catalog` — `products`, `brands`, `vendors`, `mnn_groups`.
- `promo` — `promos`, `promo_pharmacies`.
- `rules` — `rules` (с jsonb для trigger/recommend/advantages), `rule_metrics`.
- `receipts` — `receipts` (status, score, flag, photo_s3_key), `receipt_items`, `pending_bonuses`.
- `pharmacies` — `chains`, `pharmacies`.
- `payouts` — `payout_batches`, `payout_items`.
- `screens` — `playlists`, `slides`, `playlist_slides`.
- `lms` — `courses`, `lessons`, `course_progress`.
- `ai_exam` — `exam_bank`, `exam_sessions`, `exam_results`, `certificates`.

  3.2 **REST API** — RESTful endpoints на каждый домен. Контракт OpenAPI генерируется автоматически (springdoc-openapi-ui). Минимальный список (CRUD-полный для админки, read-only для мобилки):

- `POST /api/auth/sms/request` + `POST /api/auth/sms/verify` (мобилка, контракт совпадает с моком `544544`).
- `POST /api/admin/auth/login` (HQ user + JWT).
- `GET /api/admin/rules` + `POST` + `PATCH` + `DELETE` + `POST /:id/duplicate` + `POST /:id/archive`.
- Аналогично для `promo`, `pharmacies`, `pharmacists`, `screens`, `lms`, `ai-exam`, `reconcile`, `finance/batches`, `finance/batches/:id/approve`, `dashboard/summary`.
- `GET /api/mobile/balance` + `GET /api/mobile/promos` + `POST /api/mobile/receipts` + `GET /api/mobile/receipts` (для Flutter).

  3.3 **JWT + Spring Security**: роли `PHARMACIST`, `HQ_ADMIN`, `BRAND_MANAGER`. Метод-уровневая защита через `@PreAuthorize`.

  3.4 **Сидинг dev-данных** — `data.sql` или Spring Boot `CommandLineRunner` создаёт ту же фикстуру из `references/data.jsx` (8 сетей, 48 фармацевтов, 5 кампаний, 4 правила замены, 2 cross-sell), чтобы фронт-демо не пустовал.

  3.5 **Фронт-флипа моков** — MSW handlers выключаются переменной окружения `VITE_USE_MSW=false`, и тот же код через `axios`/`fetch` идёт в реальный backend.

**Verify:** `docker-compose up` поднимает всё; `bootRun` стартует, миграции применяются; `npm run dev -- --mode=real` — админка показывает реальные данные из PG, CRUD работает; Flutter с подменённым `baseUrl` показывает реальный баланс.

### Этап 4 — Recipe flow + AI-Exam end-to-end (5–7 дней)

**Цель:** загрузка чеков из мобилки реально складывается в S3 + попадает в очередь сверки в админке; AI-экзамен пишет результат, начисляет бонус.

Что делаем:

- `POST /api/mobile/receipts` принимает multipart с фото → кладёт в MinIO/S3, создаёт запись со статусом `awaiting_receipt` → если есть pending bonus от POSM → меняет статус на `in_review` → enqueue OCR job.
- `OcrService` интерфейс с заглушкой `MockOcrService` (возвращает score 0.6–0.99 случайно). Под продакшн позже добавим `YandexOcrService`.
- В админке `Reconcile` теперь видит реальные чеки, кнопки approve/reject обновляют статус + начисляют бонус на баланс фармацевта (запись в `pending_bonuses` → переходит в `payout_batches` 1 числа cron-задачей).
- AI-Exam — упрощённый: pharmacist открывает экзамен в мобилке, бэк случайно выбирает 5 вопросов из `exam_bank`, ответы оцениваются по совпадению ключевых слов (примитивно, на этапе MVP), результат → запись + сертификат-флаг (`+20%` к стандартному бонусу).

**Verify:** загружаем чек в Flutter-симе → виден в Reconcile-очереди админки → approve → у фармацевта в `pharmacists.balance` появляется бонус.

### Этап 5 — Module 2: POSM в Стандарт-Н (10–15 дней)

**Цель:** Electron-sidecar на windows-машине POS-моноблока показывает popup-рекомендацию при сканировании товара в Стандарт-Н + второй монитор клиента переключается между Idle/Active/Promo. Backend Rules Engine отдаёт ответ за <300 мс.

Backend `posm/`:

- `POST /api/posm/recommend` — body: `{pharmacyId, pharmacistId, scannedSku, currentCart: [{sku, qty}]}`. Logic: матчит правила (substitution → cross-sell, лимит 2/чек, по приоритету бонуса). Returns: `{recommendations: [{ruleId, kind, replaceSku?, suggestSku, script, advantages, bonus, dueDate}]}`.
- `POST /api/posm/replacements` — body: `{ruleId, pharmacistId, pharmacyId, originalSku, replacementSku, accepted: boolean}`. При `accepted=true` создаёт `pending_bonuses` запись, ждёт чек.
- `POST /api/posm/cdp/lookup` (ТЗ §5.6 + §4 «форма ввода телефона») — body `{phone}`. Заглушка CDP-интеграции: ищет в нашей же таблице `cdp_profiles` (создадим простую) или возвращает `{found: false}`. По полному CDP — отдельный модуль в будущем.
- `POST /api/posm/cdp/register` — создаёт профиль, отправляет SMS (через Mobizon заглушку), фиксирует «pharmacist привлёк» с pending second-bonus на 14 дней.
- WebSocket / SSE `/api/posm/events` — для второго монитора (клиентский экран): сервер пушит `{kind: 'idle'|'active'|'promo', payload}`.

Electron sidecar (`posm-sidecar/`):

- Главное окно — frameless, всегда сверху над Стандарт-Н, активируется по триггеру (см. ниже).
- **Триггер сканирования**: поскольку реального API Стандарт-Н у нас нет — для MVP делаем «sidecar» вариант 2 из ТЗ §4: поллим CSV/JSON-файл, который пишет Стандарт-Н (или используем PoC mock-кнопку «Сканировать»). Под реальную интеграцию — отдельная фаза после первых тестов в одной аптеке.
- Popup-UI: тот же визуальный язык что Figure 33 в ТЗ — сравнение товаров, скрипт, бонус, кнопки «Пропустить» / «Заменить (F9)». Используем те же Tailwind-токены из `design-tokens-admin.md`, чтобы выглядело родным семейством.
- Клиентский экран — отдельное Electron BrowserWindow в kiosk-mode на втором мониторе. Слушает SSE/WebSocket → переключает режим. В Idle крутит плейлист с того же `playlists` API.
- Форма ввода телефона клиента — отдельный экран в sidecar (см. §4 ТЗ): inline-поиск через `/api/posm/cdp/lookup` после 3-4 цифр, кнопка «Создать новый».

**Verify:** запускаем sidecar локально (без реального Стандарт-Н, через PoC mock-кнопку «Сканирую Аквалор Норм спрей»), видим popup с правилом `r_001` → нажимаем «Заменить», в админке Reconcile появляется ожидание чека от тестового фармацевта. Параллельно второй монитор переключился из Idle в Promo на 10 сек.

### Этап 6 — Подключение мобильного приложения к backend (3–5 дней)

**Цель:** Flutter переключает все feature-репозитории с in-memory моков на HTTP-вариант. Сохраняем интерфейс репозиториев (см. `claude-notes.md` «Открытые вопросы»).

- Создаём `ApiClient` в `lib/core/network/` (Dio + JWT-refresh interceptor + base URL из flavor).
- Под каждый mock-репозиторий пишем `*ApiRepository` рядом и переключаем через Riverpod-провайдер по env-флагу (`api` vs `mock`).
- Контракт авторизации (`/auth/sms/request` + `/auth/sms/verify`) уже совпадает по форме — `544544` остаётся как dev-only OTP в Spring-профиле `dev`.

**Verify:** на dev-симуляторе включаем `--dart-define=API=real`, проходим полный flow: phone → OTP → Home (баланс из бэка) → загрузка чека → SuccessScreen → история показывает новый чек.

### Этап 7 — Operational polish (по мере необходимости)

Не блокирует MVP, но обязателен до прод-релиза:

- CI/CD pipelines, Docker-образы, deployment manifests.
- Audit log в backend (`audit_events` таблица + interceptor) — кто/когда/что в админке поменял.
- Sentry (или альтернатива) на фронте и бэке.
- Rate limiting (Bucket4j через Spring) на критических endpoint'ах (`/auth/sms/request`, `/posm/recommend`).
- Locale en/kk через `flutter_localizations` + ARB (опц., упомянуто в `claude-notes.md` «Открытые вопросы»).
- Полная замена `MockOcrService` на реальный (Yandex Vision или Tesseract в Docker).

---

## Что НЕ делаем сейчас (out of scope для этого плана)

- Module 3 (интернет-магазин + PIM + CMS + 10 маркетплейсов + CDP/Maxma — ТЗ §5). Это отдельная команда и отдельный roadmap.
- Полноценный AI-экзамен с Whisper/Claude/ElevenLabs (ТЗ §3.4) — на MVP остаётся «keyword-matching» оценка, голосовой stack в Этапе 7+.
- Astana Hub юр-оформление, налоговая структура (ТЗ §3.6) — параллельный non-tech трек.
- Sidecar → Plugin/Extension переход для POSM (ТЗ §4 рекомендация «через 6 мес») — пока живём в sidecar.
- Health-app расширение (ТЗ §5.7) — горизонт после M9+.

---

## Открытые вопросы — нужно решить перед стартом

1. **Монорепо или отдельные репо?** По умолчанию идём в монорепо (`PharmaPayV2/admin-panel/web` + `PharmaPayV2/backend` + `PharmaPayV2/posm-sidecar`), но если бэкендеры предпочитают отдельный git — поднимем три репозитория и общий npm-пакет с TS-типами.
2. **CI**: GitHub Actions, GitLab CI или что-то локальное? Влияет на конфиги в Этапе 0.
3. **БД на старте**: PostgreSQL 16 OK? ClickHouse и Redis из ТЗ §2 пока не нужны — Redis заведём в Этапе 5 (когда появятся очереди и event-стрим к sidecar), ClickHouse — параллельно с аналитикой lift (Этап 7+).
4. **OCR-провайдер на проде**: Yandex Vision API, Google Document AI или открытый Tesseract в Docker? На MVP заглушка.
5. **SMS-агрегатор**: Mobizon упомянут в ТЗ §5.4 — кладём как primary; ему нужен API-ключ заранее.
6. **POSM**: с какой аптекой делаем первый pilot и с какой версией Стандарт-Н? От этого зависит, файло-обмен или БД-listen.

---

## Что делаем прямо сейчас (после approval плана)

1. Cоздаём `admin-panel/web/` — Vite + TS + Tailwind с полной палитрой из `design-tokens-admin.md`.
2. Поднимаем layout (Sidebar + Topbar + CommandPalette) — это Этап 1 целиком.
3. После layout — берёмся за **Rules Engine** как первую секцию (самая жирная и самая важная по ТЗ).

После Этапа 1 показываем демо стейкхолдерам и решаем, по какому порядку секций двигаться дальше.
