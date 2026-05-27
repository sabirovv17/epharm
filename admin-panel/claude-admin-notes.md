# Epharm Admin Console + Backend + POSM — рабочие заметки

> **Читать этот файл первым в каждой новой сессии по админ-панели / бэкенду / POSM.**
> Сжатое описание состояния, решения, гочи. Цель — не тратить токены на повторное изучение `references/`, `design-tokens-admin.md`, ТЗ и Spring-кода.

## Что строим

Три параллельные ветки одной экосистемы Epharm (бренд) / Ledex × Inkar (юр.основа), описанные в `ИТОГОВОЕ_ТЗ.pdf`:

1. **Admin Console (HQ web)** — React-фронт для категорийной команды Inkar и бренд-менеджеров (управляет правилами замен, кампаниями, выплатами, сверкой чеков, экранами в аптеках, LMS, AI-экзаменом). Эталон UX готов — `admin-panel/references/` (JSX через `<script type="text/babel">` + `window.AD` fixtures).
2. **Backend** — Kotlin 2.0 + Spring Boot 3.3 монолит. Покрывает мобильное приложение фармацевта (Flutter, уже готовый фронт) и админ-консоль через REST.
3. **Module 2 — POSM в Стандарт-Н** — Electron-sidecar на POS-моноблоке аптеки, который показывает popup-рекомендацию замены / cross-sell при сканировании товара + управляет вторым 15"-монитором клиента (Idle/Active/Promo).

Mobile-фронт (Flutter) уже работает на mock-репозиториях — см. `claude-notes.md` в корне.

## Стек (утверждено пользователем)

| Слой                 | Что выбрано                                                                                               |
| -------------------- | --------------------------------------------------------------------------------------------------------- |
| Admin frontend       | **React 18 + Vite + TypeScript + Tailwind 3** + React Router v6 + TanStack Query + Zustand + lucide-react |
| Admin mocks (фаза 1) | **MSW** (Mock Service Worker) — handlers конвертируют `references/data.jsx` фикстуры в HTTP-ответы        |
| Backend              | **Kotlin 2.0** + **Spring Boot 3.3** (Web, Security, Data JPA, Validation, Actuator) + Gradle Kotlin DSL  |
| DB                   | PostgreSQL 16 + Flyway + Hibernate/JPA                                                                    |
| Auth                 | Spring Security + JWT (access 15 мин / refresh 30 дней) + bcrypt (admin) + SMS-OTP (pharmacist)           |
| Files                | S3-compatible (MinIO dev / Yandex Object Storage prod)                                                    |
| Cache / queues       | Redis (заводим в Этапе 5 под POSM-events)                                                                 |
| OCR                  | заглушка `MockOcrService` на MVP, потом Yandex Vision                                                     |
| POSM sidecar         | Electron 30 + React (тот же стек, что админка)                                                            |

**Языковая версия Kotlin:** 2.0 (НЕ 1.9). `kotlin = "2.0.x"` в `gradle/libs.versions.toml`.

## Структура (монорепо PharmaPayV2/)

```
PharmaPayV2/
├── lib/                          # Flutter mobile app (как есть)
├── admin-panel/
│   ├── design-tokens-admin.md    # source-of-truth дизайн-системы админки
│   ├── references/               # JSX-эталон (source-of-truth UX, не трогаем)
│   ├── PLAN.md                   # верхнеуровневый план разработки
│   ├── claude-admin-notes.md     # ЭТОТ файл — рабочие заметки
│   └── web/                      # production React (создаётся в Этапе 0)
├── backend/                      # Kotlin 2.0 + Spring Boot 3.3 (Этап 0)
└── posm-sidecar/                 # Electron-клиент Module 2 (Этап 5)
```

## Ключевые соглашения

### Admin frontend

- **Дизайн-токены — source-of-truth `admin-panel/design-tokens-admin.md`**. Любая палитра/радиус/тень/типографика — оттуда. Хексы в коде НЕ хардкодим, только Tailwind-токены (`bg-brand-green-600`, `text-ink-900`, и т.д.).
- **JSX-эталон `references/`** — это **визуальный и поведенческий source-of-truth**, но НЕ копия для прод-кода. Production-React использует TS, модули, импорты вместо `Object.assign(window, ...)`. Когда переносим секцию — открываем `references/sections/<name>.jsx`, читаем структуру и переносим её в `admin-panel/web/src/features/<name>/`.
- **Деньги:** все суммы через `formatKzt(n)` → `1 842 300 ₸` (NBSP-группировка, `Intl.NumberFormat('ru-RU')`). НИКОГДА не сокращаем «1.84 М ₸».
- **Числа в таблицах:** класс `.num` (`font-variant-numeric: tabular-nums`) + JetBrains Mono → колонки выравниваются.
- **Layout breakpoints:** root `min-width: 1280px`. Mobile-адаптации для админки **не делаем** (mobile = отдельное Flutter-приложение).
- **State management:**
  - **Server state** → TanStack Query (включая моки через MSW).
  - **Global UI state** (sidebar collapsed, current role, command palette open, toasts) → Zustand с single store.
  - **Local component state** → `useState`.

### Backend

- **Гайдлайн пакетов:** `kz.epharm.<domain>` — `auth`, `catalog`, `promo`, `rules`, `receipts`, `pharmacies`, `pharmacists`, `payouts`, `lms`, `screens`, `ai_exam`, `posm`, `shared`.
- **Внутри домена:** `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `mapper/`.
- **DTO ≠ Entity.** Никогда не возвращаем JPA-entity напрямую из контроллера.
- **Миграции:** Flyway, `V<NNN>__<snake_case>.sql`. Никаких `ddl-auto: update` даже в dev.
- **Тесты:** JUnit 5 + Testcontainers (PostgreSQL) для интеграционных. MockK для unit.
- **API-стиль:** REST, kebab-case URL, plural resources (`/api/admin/rules`, `/api/admin/payout-batches`).
- **Контракт мобильного приложения:** `/api/auth/sms/{request,verify}` — `544544` остаётся как dev-only OTP в Spring-профиле `dev` (чтобы совпало с моком `lib/features/auth/data/auth_repository.dart`).
- **Roles:** `PHARMACIST`, `HQ_ADMIN`, `BRAND_MANAGER`, `CATEGORY_LEAD`, `FINANCE_REVIEWER`. Метод-уровневая защита через `@PreAuthorize("hasRole('HQ_ADMIN')")`.

### POSM sidecar

- **Поверх Стандарт-Н:** frameless BrowserWindow, `alwaysOnTop`. Опрос/слушание событий Стандарт-Н — вариант 2 из ТЗ §4 (sidecar). На MVP — мок-кнопка «Сканирую X», под реальный пилот — file-watcher на CSV/JSON, который пишет Стандарт-Н.
- **Клиентский экран:** отдельное Electron BrowserWindow kiosk на втором мониторе, режимы Idle/Active/Promo переключаются через SSE/WebSocket к backend.

## Что уже сделано

### Этап 0 (в процессе, 2026-05-26)

- ✅ **План** (`plan.md` в Claude + `admin-panel/PLAN.md`) утверждён в plan mode.
- ✅ **`docker-compose.yml`** в корне `PharmaPayV2/` — `postgres:16-alpine` + `redis:7-alpine` + `minio/minio` + одноразовый `minio-init` (создаёт bucket `epharm-receipts`). Все healthchecks работают.
  - **Gotcha**: порт 5432 хоста часто занят системным Postgres (на dev-машине Amir был свой). Мапим как **`5433:5432`**. application-dev.yml ходит на `localhost:5433`.
  - **Gotcha**: `version: "3.9"` в compose-файле — устарел, удалён.
  - Команда: `docker compose up -d`. Health: `docker exec epharm-postgres pg_isready -U epharm -d epharm` → accepting.
- ✅ **`backend/`** — Kotlin 2.0.21 + Spring Boot 3.3.5 + Gradle wrapper 8.10.2 + JVM toolchain 22 (Temurin).
  - `build.gradle.kts` + `gradle/libs.versions.toml` (version-catalog паттерн).
  - Dependencies: Web, Security, Data JPA, Data Redis, Validation, Actuator, devtools, JWT (jjwt 0.12.6), Postgres, Flyway core+postgresql, AWS S3 SDK v2, springdoc-openapi 2.6, Testcontainers, MockK + springmockk.
  - `EpharmApplication.kt` + `shared/HealthController.kt` (`GET /api/health` → `{service, version, status, timestamp}`).
  - `application.yml` (общие настройки) + `application-dev.yml` (DB/Redis для localhost).
  - `V001__init.sql` — пустая init-миграция с marker-таблицей `schema_meta`.
  - **Gotcha — Kotlin plugin не резолвился**: дефолтный `settings.gradle.kts` без `pluginManagement` не подхватывал Gradle Plugin Portal в этой среде. Решение: явно добавить `pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }`. Сейчас в settings.gradle.kts.
  - Команды: `./gradlew bootRun` (нужны JAVA_HOME=Temurin 22 + поднятая инфра), `./gradlew test`.
- ✅ **`admin-panel/web/`** — React 19 + Vite 7 + TypeScript + Tailwind 3.4.
  - Зависимости: react-router-dom v6, @tanstack/react-query v5, zustand v5, lucide-react, @fontsource/manrope + @fontsource/jetbrains-mono, clsx, tailwind-merge, msw v2.
  - `tailwind.config.ts` — полная палитра из `design-tokens-admin.md`: brand.green 50-800 + brand.blue 100-700 + ink 50-900 + paper.{DEFAULT,card,input,hover} + accent + surface (danger/warning) + shadow токены + radii 4/6/10/12/16/20 + minWidth.screen=1280.
  - `index.css` — `@fontsource` импорты + tailwind базы + `.num` utility (tabular-nums) + минимум-1280 на #root.
  - `App.tsx` — стартовый экран с swatch-плиткой green/{50…700}, тестовыми кнопками primary/outline, chip-green и `1 842 300 ₸` через JetBrains Mono. Все стили — только Tailwind-токены.
  - **Verify**: `npx tsc --noEmit` ✓ clean, `npm run build` ✓ 2.68s (193 KB JS / 60 KB gzip + 76 KB CSS).
- ✅ **CI** — `.github/workflows/ci.yml`: два job'а — admin-web (Node 22 → lint → tsc → build) + backend (Temurin 22 + Postgres service → gradle build → gradle test).

## Команды для daily-разработки

```bash
# Инфра
docker compose up -d                                  # Postgres:5433, Redis:6379, MinIO:9000/9001
docker compose down                                   # выкл
docker compose down -v                                # выкл + удалить volumes (чистый старт)

# Backend
cd backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew bootRun                                     # localhost:8080, profile=dev
./gradlew test                                        # unit + integration (Testcontainers)
./gradlew build -x test                               # только компиляция

# Admin web
cd admin-panel/web
npm run dev                                           # localhost:5173
npm run build                                         # prod-сборка в dist/
npx tsc --noEmit                                      # type-check без эмита
```

## Версии (зафиксированы)

| Что               | Версия                | Где                                                |
| ----------------- | --------------------- | -------------------------------------------------- |
| Kotlin            | 2.0.21                | `backend/gradle/libs.versions.toml`                |
| Spring Boot       | 3.3.5                 | то же                                              |
| Gradle wrapper    | 8.10.2                | `backend/gradle/wrapper/gradle-wrapper.properties` |
| JVM toolchain     | 22 (Temurin)          | `backend/build.gradle.kts` (java { toolchain })    |
| jjwt              | 0.12.6                | libs.versions.toml                                 |
| AWS SDK v2 (S3)   | 2.29.9                | то же                                              |
| springdoc-openapi | 2.6.0                 | то же                                              |
| Flyway            | 10.20.1               | то же                                              |
| Testcontainers    | 1.20.3                | то же                                              |
| Node              | 22+ (CI) / 26 (local) | `.github/workflows/ci.yml`, `brew install node`    |
| Vite              | 7.x                   | `admin-panel/web/package.json`                     |
| React             | 19.x                  | то же                                              |
| Tailwind          | 3.4.x                 | то же                                              |
| TanStack Query    | 5.59.x                | то же                                              |
| Zustand           | 5.0.x                 | то же                                              |
| MSW               | 2.6.x                 | то же (заведём handlers в Этапе 2)                 |

## Открытые вопросы (не блокеры, но решить до Этапа 3+)

1. CI: GitHub Actions vs GitLab vs локальный — нужно для Этапа 0.
2. SMS-провайдер для prod: Mobizon API-ключ.
3. OCR-провайдер для prod: Yandex Vision vs Google Document AI vs Tesseract.
4. POSM-пилот: какая аптека и какая версия Стандарт-Н — определит реальную интеграцию (file-watch / DB-listen / HTTP-callback из Стандарт-Н).
5. Хостинг: VPS / Yandex Cloud / AWS — влияет на Terraform/Ansible в Этапе 7.

## Лог изменений этого файла

- **2026-05-26 (создан)** — initial bootstrap. Утверждённые решения: Kotlin 2.0, React+Vite+TS+Tailwind, монорепо. Готов план в `PLAN.md` + этот notes-файл. Ничего по разработке пока не сделано — стартуем с Этапа 0.
- **2026-05-26 — Этап 0 завершён.** Все 5 задач bootstrap'а закрыты, верификация прошла end-to-end:
  - `docker compose up -d` — Postgres:5433 + Redis:6379 + MinIO:9000-9001 все healthy.
  - `./gradlew bootRun` — Spring Boot стартует за 12 сек, Flyway применил V001\_\_init, Postgres подключен через Hikari, Tomcat на 8080.
  - `curl http://localhost:8080/api/health` → `{"service":"epharm-backend","version":"0.1.0","status":"ok","timestamp":"..."}`.
  - `./gradlew test` — `HealthControllerTest` зелёный (56 сек, инфраструктурные autoconfigs исключены).
  - `npm run dev` — Vite 8 запускается за 1.88 сек на localhost:5173, рендерит canvas paper/DEFAULT + swatch-плитку + JetBrains Mono для `1 842 300 ₸`.
  - `npm run build` → 193 KB JS / 60 KB gzip + 76 KB CSS, TypeScript clean.
  - **Gotcha #1 — Spring Security блочит /api/health по умолчанию.** Добавлен `shared/SecurityConfig.kt`: permitAll на `/api/health`, `/actuator/health|info`, `/v3/api-docs/**`, `/swagger-ui/**`. Остальное `authenticated()` (расширим в Этапе 3 — JWT-фильтр + per-endpoint роли).
  - **Gotcha #2 — Vite 8 не вернул favicon.svg** (удалил при cleanup'е) — пока неважно, в Этапе 1 будет наш свой.
  - **Gotcha #3 — Gradle deprecation warning** «incompatible with Gradle 9.0» — Spring Boot 3.3 + Kotlin 2.0 пока не на 9.0. Запустим `--warning-mode all` при апгрейде до Gradle 9.x в Этапе 7.

## Следующее действие

**Этап 1 — Admin Layout + UI-kit** (3-5 дней):

1. Создать структуру `admin-panel/web/src/{app,layout,ui,lib,features,mocks,types}/`.
2. Перенести `references/ui.jsx` → набор TS-компонентов в `web/src/ui/` (Button, Input, Select, Toggle, Tabs, StatusChip, Modal, Drawer, ToastHost, Metric, SectionCard, Sparkline, ComingSoonBanner и т.д.).
3. Перенести `references/icons.jsx` → `web/src/ui/icons.tsx` (lucide-react где совпадает, кастом SVG для brand-glyph'ов).
4. Перенести `references/layout.jsx` → Sidebar / Topbar / CommandPalette / RoleSwitcher / ContractModal.
5. Перенести `references/app.jsx` → `web/src/app/{App.tsx, router.tsx}` с React Router v6 на 12 routes, каждая секция = ComingSoonBanner stub.
6. ⌘K listener в `App.tsx`, Zustand store для sidebar.collapsed + commandPaletteOpen + activeRole.
7. Verify: проход по 12 пунктам Sidebar без ошибок в консоли, ⌘K открывается, role-switcher работает.
