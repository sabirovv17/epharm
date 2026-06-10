# Epharm — документация проекта

> Полная техническая документация экосистемы **Epharm** (Ledex × Inkar).
> Эта папка — единый источник правды по всему проекту: архитектура, бэкенд, админка,
> мобильное приложение, POSM-модуль для касс, деплой и эксплуатация.

📌 **Эта папка `docs/` НЕ деплоится на сервер** — она только в репозитории, для команды.
Серверный деплой собирается из конкретных путей (`admin-panel/backend`, `admin-panel/frontend`,
`docker-compose.prod.yml`, `Caddyfile`, `tools`), `docs/` туда не попадает.

---

## Что это за продукт

**Epharm** — IT-экосистема мотивации фармацевтов и программы лояльности для аптечной сети.
Фармацевт рекомендует на кассе нужный препарат (замена / допродажа), подтверждает продажу
чеком — и получает бонус. HQ-команда (штаб Inkar/Ledex) управляет правилами рекомендаций,
аптеками, фармацевтами и выплатами через веб-админку.

Бренд продукта — **Epharm**. Заказчик/сеть — **Inkar**, разработка — **Ledex**.

## Три модуля системы

| Модуль             | Что это                                                                                | Технологии                      | Где в репо             |
| ------------------ | -------------------------------------------------------------------------------------- | ------------------------------- | ---------------------- |
| **M1 — PharmaPay** | Приложение фармацевта + HQ-админка + бэкенд (auth, правила, чеки, выплаты, AI-экзамен) | Flutter · Kotlin/Spring · React | `lib/`, `admin-panel/` |
| **M2 — POSM**      | Sidecar внутри кассовой программы «Стандарт-Н»: попап-рекомендации + клиентский экран  | C# / WPF (.NET 10)              | `App/`, `Models/`      |
| **M3 — Витрина**   | Внешний каталог (интернет-магазин на Medusa) — read-only источник товаров для мобилки  | Medusa (внешний)                | интеграция в backend   |

> M3 (полноценный e-com, PIM, маркетплейсы) — отдельная команда и roadmap. В этом репозитории
> используется только как **источник реального каталога** (~523 товара/аптеки) через REST.

## Карта документации

| Файл                                                   | О чём                                                                                            |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| [`01-architecture.md`](01-architecture.md)             | Общая архитектура, API-поверхность, ключевые бизнес-потоки (чек→бонус, рекомендация→чек→выплата) |
| [`02-backend.md`](02-backend.md)                       | Бэкенд: домены, полный справочник REST API, безопасность/роли, сервисы, сборка                   |
| [`03-admin-panel.md`](03-admin-panel.md)               | Веб-админка: 13 разделов, роутинг, состояние, API-клиент, UI-kit, i18n                           |
| [`04-mobile-app.md`](04-mobile-app.md)                 | Мобильное приложение фармацевта (Flutter): фичи, сеть, data-слой, сборка APK/iOS                 |
| [`05-posm-client.md`](05-posm-client.md)               | POSM-клиент для кассы (C#): попап, клиентский экран, авто-апдейт, offline-очередь, деплой        |
| [`06-deployment-and-ops.md`](06-deployment-and-ops.md) | Деплой на сервер, docker-compose, Caddy, .env, доступ по VPN, бэкапы, эксплуатация               |
| [`07-database.md`](07-database.md)                     | Схема БД: 21 Flyway-миграция, таблицы по доменам, архитектурные решения                          |

## Стек одним взглядом

```
Mobile  : Flutter · Riverpod · go_router · http · flutter_secure_storage
Admin   : React 19 · Vite · TypeScript · Tailwind 3 · Zustand · TanStack Query · axios
Backend : Kotlin 2.0 · Spring Boot 3.3 (Web/Security/JPA/Validation/Actuator) · Gradle
DB/инфра: PostgreSQL 16 · Redis 7 · MinIO (S3) · Flyway · Docker Compose · Caddy (Let's Encrypt)
POSM    : C# / .NET 10 · WPF · LibVLCSharp (видео) · SQLite (offline outbox)
Каталог : Medusa Store API (внешний, read-only)
```

## Структура репозитория (верхний уровень)

```
PharmaPayV2/
├── lib/                     # Flutter — приложение фармацевта (M1)
├── android/ ios/ macos/     # платформенные конфиги Flutter
├── assets/                  # иконки, шрифты, картинки мобилки
├── builds/                  # build_all.sh + собранные APK
├── admin-panel/
│   ├── backend/             # Kotlin + Spring Boot монолит (M1 backend)
│   ├── frontend/            # React + Vite админка (M1 admin)
│   ├── references/          # JSX-эталон UI (UX-spec, не прод)
│   ├── PLAN.md              # верхнеуровневый roadmap
│   ├── claude-admin-notes.md# рабочие заметки бэка/админки/POSM
│   └── design-tokens-admin.md
├── App/                     # C# / WPF POSM-клиент (M2)
├── Models/                  # C# DTO, общие для POSM (Posm/*, ReceiptItem)
├── _reference/              # дизайн-токены мобилки + прототипы
├── tools/                   # gen-prod-env.sh, pg-backup.sh, генераторы иконок
├── .github/workflows/       # CI (lint/test/build)
├── docker-compose.yml       # dev-стек (Postgres + Redis + MinIO)
├── docker-compose.prod.yml  # прод-стек (всё + backend + frontend + Caddy)
├── Caddyfile                # reverse-proxy + авто-TLS
├── .env.prod.example        # шаблон прод-секретов
└── docs/                    # ← эта документация
```

## Текущее состояние (на момент написания)

- ✅ **Деплой на сервере `inkpim.inkar.kz` (10.10.1.76)** — все 6 сервисов healthy
  (postgres, redis, minio, backend, frontend, caddy). Конфиг сервера сверен с git (HEAD).
- ✅ Бэкенд: 21 миграция, ~23 контроллера, реальный каталог из Medusa, первый админ создан
  через `ProdBootstrap` (`admin@epharm.kz`).
- ✅ Админка отдаётся по VPN на внутреннем имени `inkpim.inkar.kz` (см. `06-deployment-and-ops.md`).
- ✅ Мобильный прод-APK собирается (`builds/build_all.sh`, `USE_API=true`, `API_BASE=https://api.epharm.kz`).
- ⏳ Публичные домены `*.epharm.kz` + проброс портов 80/443 — ожидают служебку сетевикам INKAR.
- ⏳ Открытые задачи безопасности перед публичным go-live: приватный бакет чеков (P0-6),
  ротация утёкших в чат секретов, выключение dev-OTP. См. `RELEASE-CHECKLIST.md` в корне.

## Связанные документы в корне репозитория

- `README.md` — короткий обзор + quick start
- `RUNBOOK.md` — локальный запуск (Docker + backend + frontend + мобилка)
- `RELEASE-CHECKLIST.md` — чек-лист прод-готовности (P0/P1/P2)
- `CONTRIBUTING.md` — git-flow, conventional commits
- `admin-panel/PLAN.md` — изначальный план этапов 0–7
- `App/POSM_DEPLOY.md`, `App/WINDOWS_RUNBOOK.md` — деплой POSM на кассу
