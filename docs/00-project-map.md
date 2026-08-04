# Карта проекта (00)

Единственная актуальная карта репозитория. Цель — найти нужный код/док за один переход,
не сканируя дерево. Обновлять при структурных изменениях (перенос папок, новые модули).

## Верхний уровень

```text
PharmaPayV2/
├── CLAUDE.md                ← контракт для Claude (грузится автоматически каждую сессию)
├── README.md                ← обзор репо + quick start (для людей/GitHub)
├── CONTRIBUTING.md          ← git-конвенции (commitlint, PR)
├── docs/                    ← ВСЯ документация (см. docs/README.md — индекс)
│
├── lib/                     ← Flutter мобильное приложение (Dart)
├── test/                    ← Flutter-тесты
├── android/ ios/ macos/     ← платформенные обвязки Flutter
├── assets/                  ← шрифты/картинки мобилки
│
├── admin-panel/
│   ├── backend/             ← Kotlin 2.0 + Spring Boot 3.3 (ЕДИНЫЙ бэкенд всего продукта)
│   ├── frontend/            ← React 19 + Vite + TS (HQ-админка)
│   ├── claude-admin-notes.md← рабочая память админки/бэка/POSM (живой док)
│   ├── design-tokens-admin.md
│   ├── POSM_INTEGRATION.md  ← как POSM говорит с бэком
│   └── PLAN.md              ← исторический план (не источник правды)
│
├── App/                     ← C#/WPF .NET 10 POSM-клиент кассы (Стандарт-Н)
│   ├── scripts/             ← автозапуск/watchdog/разведка Стандарт-Н (.ps1/.bat)
│   ├── POSM_DEPLOY.md, WINDOWS_RUNBOOK.md  ← модульные доки POSM
├── Models/                  ← общие C#-модели (POSM DTO)
│
├── docker-compose.yml       ← dev-инфра (Postgres 16, Redis, MinIO)
├── docker-compose.prod.yml  ← прод-стек (+ backend, frontend, Caddy)
├── Caddyfile                ← reverse-proxy прод (один хост: /api, /s3, /)
├── .env.prod.example        ← шаблон прод-секретов (сам .env.prod ТОЛЬКО на сервере)
│
├── builds/                  ← архив собранных релизов (APK/zip) + build_all.sh
├── tools/                   ← серверные утилиты (pg-backup.sh)
├── _reference/              ← дизайн-токены и handoff (исторический контекст)
└── dist/, build*/           ← артефакты сборок (gitignored, можно сносить)
```

## Модули и их входные точки

| Модуль          | Код                                              | Тесты                      | Ключевые файлы                                                                                                                           |
| --------------- | ------------------------------------------------ | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| **Backend**     | `admin-panel/backend/src/main/kotlin/kz/epharm/` | `src/test/kotlin/`         | `application.yml` (вся конфигурация + env-переменные); Flyway: `src/main/resources/db/migration/` (V001–V036)                            |
| **Админ-фронт** | `admin-panel/frontend/src/`                      | `*.test.tsx` рядом с кодом | `features/*/Page.tsx` (12 разделов), `lib/api-types.ts`, `lib/queries/*`, `i18n/dict.ts` (ru+kk)                                         |
| **Мобилка**     | `lib/`                                           | `test/`                    | `core/config/api_config.dart` (USE_API/API_BASE), `core/network/api_client.dart`, `features/*/{data,application,presentation}`           |
| **POSM**        | `App/` + `Models/`                               | ручное на VM               | `MainWindow.xaml.cs` (лог кассы), `MainWindow.Recommendations.cs`, `Services/` (Api/Outbox/MediaCache/Updater), `Config/EpharmConfig.cs` |

## Backend: пакеты kz.epharm.\*

| Пакет                                             | Отвечает за                                                                        |
| ------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `auth`                                            | JWT-вход админки (admin_users, refresh)                                            |
| `mobile.auth`                                     | OTP-вход фармацевта (p1sms SMS, `P1smsSender`, кулдаун)                            |
| `mobile.catalog`                                  | каталог мобилки из Medusa + рекомендации карточки (двунаправленные) + fallback-Q&A |
| `mobile` (остальное)                              | me/баланс, чеки, аптеки рядом                                                      |
| `posm`                                            | recommend/sales/heartbeat/плейлисты касс + атрибуция показ→продажа (V032)          |
| `training`                                        | программы, маршруты, назначения, события, результаты, сертификаты и бонусы         |
| `rules`                                           | движок правил замены/кросс-селла (read-only, источник = Промо)                     |
| `promo`                                           | Промо-кампании (1 кампания = 1 товар; правила создаются отсюда)                    |
| `medusa`                                          | клиент витрины inkar.kz (каталог/цены/фото) + image-прокси                         |
| `screens`                                         | слайды/плейлисты видео для 2-х экранов касс (MinIO)                                |
| `receipts`/`reconcile`                            | чеки мобилки + модерация бонусов                                                   |
| `dashboard`                                       | сводка + журнал показов/продаж (RecommendationAnalytics)                           |
| `pharmacies`/`pharmacists`/`finance`/`lms`/`exam` | справочники и разделы админки                                                      |
| `shared`                                          | SecurityConfig, ошибки (`AppException`/`ErrorCode`), `PhoneUtil`, ProdBootstrap    |

## Важные файлы (когда что-то надо быстро)

| Задача                           | Файл                                                                   |
| -------------------------------- | ---------------------------------------------------------------------- |
| Конфиг/env бэка (все переменные) | `admin-panel/backend/src/main/resources/application.yml`               |
| Прод-деплой (как катить)         | `docs/06-deployment-and-ops.md` §Deploy From Git                       |
| Прод-координаты/операции         | `docs/RUNBOOK.md` + `tools/pg-backup.sh`                               |
| SMS-вход (провайдер p1sms)       | `…/mobile/auth/service/P1smsSender.kt` + `OtpService.kt`               |
| Рекомендации в карточке мобилки  | `…/mobile/catalog/service/MobileCatalogService.kt`                     |
| POSM: живой чек Стандарт-Н       | `App/MainWindow.StandardNReceipt.cs` + `Services/StandardNDbLookup.cs` |
| POSM: fallback-парсер лога       | `App/MainWindow.xaml.cs` (`ProcessLogLine`/`TryParseAdd2Cheque`)       |
| POSM: сборка дистрибутива        | `~/Desktop/work/epharm-demo/publish-exe.ps1` (вне git, шара Z:)        |
| Разведка Стандарт-Н на VM        | `App/scripts/standartn-discover.ps1`                                   |
| Мобилка: вход/OTP-экран          | `lib/features/auth/presentation/otp_screen.dart`                       |
| Мобилка: сборка APK/iOS          | `docs/DEV-ONBOARDING.md`; APK всегда → MinIO `epharm-demo.apk`         |
| Секреты витрины/SSH              | `docs/STOREFRONT-CREDENTIALS.md` (untracked!)                          |

## Внешние системы

| Система                   | Адрес                                                           | Заметки                                                                         |
| ------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Прод-сервер               | `adm-quasar@inkpim.inkar.kz`, каталог `/home/adm-quasar/epharm` | деплой = git archive + scp + compose build                                      |
| Публичный хост            | `https://epharm.inkar.kz`                                       | `/api`→backend, `/s3`→MinIO, `/`→админка                                        |
| Medusa (витрина inkar.kz) | `http://78.140.246.238:9000`                                    | каталог/цены/фото; ключи в application.yml                                      |
| p1sms (SMS)               | `https://admin.p1sms.kz/apiSms/create`                          | ключ в `.env.prod` (`P1SMS_API_KEY`)                                            |
| Стандарт-Н ДЕМО           | VM пользователя, `C:\Standart-N_DEMO`                           | Firebird `db/ztrade.fdb` (localhost, SYSDBA/masterkey), лог `Kassir/zkassa.log` |
| Шара Mac↔VM               | Mac `~/Desktop/work` = VM `Z:\`                                 | `Z:\epharm-demo` — стейджинг POSM                                               |
