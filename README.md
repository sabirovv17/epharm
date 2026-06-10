# Epharm

Экосистема для аптечной сети Inkar (Казахстан): мотивация фармацевтов + HQ-управление + подсказки на кассе.
Часть совместного предприятия **Ledex × Inkar** (см. `ИТОГОВОЕ_ТЗ.pdf` за пределами репо).

## Модули

| Модуль                                      | Что                                                                                                                                                         | Стек                                                           | Статус                                                      |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ----------------------------------------------------------- |
| **Mobile app** (`lib/`)                     | iOS + Android приложение фармацевта: баланс, промо, реальный каталог, аптеки, загрузка чеков                                                                | Flutter 3.27 + Riverpod + go_router                            | Реальный backend (`USE_API`), запускается на iPhone/Android |
| **Admin Console** (`admin-panel/frontend/`) | HQ web для категорийной команды Inkar и бренд-менеджеров: 13 разделов (правила замен, кампании, сверка чеков, выплаты, экраны, LMS, AI-экзамен, витрина, …) | React 19 + Vite + TS + Tailwind 3 + TanStack Query + Zustand   | Готова: 13 разделов на реальном API, прод-сборка зелёная    |
| **Backend** (`admin-panel/backend/`)        | Spring Boot монолит — REST для mobile + admin + POSM, JWT + RBAC, Flyway, S3, прокси Medusa                                                                 | Kotlin 2.0 + Spring Boot 3.3 + PostgreSQL 16 + Redis 7 + MinIO | Готов: build SUCCESSFUL, миграции V001–V021                 |
| **POSM Sidecar** (`posm-sidecar/`, Этап 5)  | Electron-клиент на POS-моноблоке аптеки: popup-рекомендации замен + второй монитор клиента                                                                  | Electron 30 + React                                            | Не начат                                                    |

> **Что НЕ делаем** в этом репозитории: Module 3 ТЗ (интернет-магазин, PIM, CMS, маркетплейсы, CDP) — это отдельная команда и отдельный roadmap.

## Документация

| Файл                                                                           | Для кого                                                                                         |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| **[admin-panel/PLAN.md](./admin-panel/PLAN.md)**                               | План разработки всей экосистемы — этапы 0-7, mapping ТЗ → код                                    |
| **[admin-panel/claude-admin-notes.md](./admin-panel/claude-admin-notes.md)**   | Живые рабочие заметки по admin / backend / POSM. **Читать первым в новой сессии по этим зонам.** |
| **[claude-notes.md](./claude-notes.md)**                                       | Живые рабочие заметки по mobile-приложению. **Читать первым в новой сессии по lib/.**            |
| **[admin-panel/design-tokens-admin.md](./admin-panel/design-tokens-admin.md)** | Источник истины дизайн-системы админки (палитра, типографика, компоненты)                        |
| **[\_reference/design-tokens.md](./_reference/design-tokens.md)**              | Источник истины дизайн-системы мобильного приложения                                             |
| **[CONTRIBUTING.md](./CONTRIBUTING.md)**                                       | Git workflow, branch naming, commit format, как открывать PR                                     |

## Быстрый запуск (Этап 0+)

```bash
# 1. Инфраструктура (Postgres 16 на 5433, Redis 7, MinIO)
docker compose up -d

# 2. Backend (требует JAVA_HOME=Temurin 22)
cd admin-panel/backend
export JAVA_HOME=/Users/<user>/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew bootRun                # → http://localhost:8080/api/health

# 3. Admin console (frontend)
cd admin-panel/frontend
npm install                      # один раз
npm run dev                      # → http://localhost:5173

# 4. Mobile (как раньше)
export PATH="$HOME/development/flutter/bin:$PATH"
flutter pub get
flutter run                      # iOS-симулятор
```

## Структура корня репо

```
PharmaPayV2/
├── admin-panel/                   # ⭐ Админ-консоль (backend + frontend + docs в одном проекте)
│   ├── backend/                   # Kotlin 2.0 + Spring Boot 3.3 (Gradle wrapper, JVM 22)
│   │   ├── src/main/kotlin/kz/epharm/  # auth/ shared/ ... (домены)
│   │   ├── src/test/kotlin/        # JUnit5 unit + Testcontainers integration
│   │   └── build.gradle.kts, gradle/
│   ├── frontend/                  # React 19 + Vite + TS + Tailwind 3
│   │   ├── src/{app, layout, ui, features, lib, mocks}/
│   │   ├── src/**/*.test.{ts,tsx} # Vitest: unit + Testing Library + smoke
│   │   └── package.json, vite.config.ts
│   ├── PLAN.md                    # план развития экосистемы (Этапы 0-7)
│   ├── claude-admin-notes.md      # живые заметки admin / backend / POSM
│   ├── design-tokens-admin.md     # дизайн-система админки
│   └── references/                # JSX-эталон 12 секций — source-of-truth UX
├── lib/                           # Flutter mobile app
├── ios/ android/ macos/ assets/   # Платформы + ассеты mobile
├── posm-sidecar/                  # Electron client (Этап 5+, не начат)
├── _reference/                    # mobile design tokens + HTML/JSX прототипы
├── docker-compose.yml             # Postgres + Redis + MinIO для local dev
├── .github/                       # CI workflows + CODEOWNERS + PR template
├── .husky/                        # pre-commit + commit-msg хуки
├── claude-notes.md                # живые заметки mobile-приложения
├── package.json                   # корневой — husky/commitlint/prettier
├── CONTRIBUTING.md                # git workflow + commit format
└── README.md                      # этот файл
```

**Принцип:** админка = один проект (`admin-panel/`), внутри 2 подпапки `backend/` (Kotlin) + `frontend/` (React). Mobile приложение фармацевта — отдельный модуль в корне (`lib/` + платформы). Mobile и admin делят общий backend через `POST /api/admin/...` / `POST /api/mobile/...` префиксы.

## Версии (зафиксированы)

| Стек             | Версия                |
| ---------------- | --------------------- |
| Flutter / Dart   | 3.27 / 3.6            |
| Kotlin           | 2.0.21                |
| Spring Boot      | 3.3.5                 |
| Gradle wrapper   | 8.10.2                |
| Java (toolchain) | Temurin 22            |
| Node             | 22 (CI) / 26+ (local) |
| Vite             | 7.x                   |
| React            | 19.x                  |
| Tailwind         | 3.4.x                 |
| PostgreSQL       | 16-alpine             |
| Redis            | 7-alpine              |
| MinIO            | latest                |

## Дизайн-токены — единственный источник правды

- **Mobile** — `_reference/design-tokens.md` (814 строк, §1-§11 включая receipt-flow и admin console refs)
- **Admin** — `admin-panel/design-tokens-admin.md` (412 строк, расширение mobile-токенов для desktop)
- **Хексы в коде запрещены** — только Tailwind-токены (`bg-brand-green-600`) или Dart-константы (`AppColors.brandGreen600`)
- Тенге `₸` в Flutter: всегда `fontFamilyFallback: ['Roboto', 'sans-serif']` — Manrope-Variable не содержит U+20B8

## Build артефакты mobile

`builds/` содержит готовые APK / IPA для ревью. Подробности — `builds/README.md`, regenerate — `bash builds/build_all.sh`.

## Lead

Амир ([@sabirovv17](https://github.com/sabirovv17)) — текущий единственный owner всего репо (см. `.github/CODEOWNERS`).
