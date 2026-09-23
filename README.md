# Epharm

Epharm — система для аптечной сети Inkar: HQ-админка, мобильное приложение фармацевта, POSM-модуль на кассах и интеграции с витриной и Medusa. Историческое имя `PharmaPayV2` по-прежнему встречается в путях и идентификаторах; переименовывать его при настройке проекта не нужно.

Рабочий публичный адрес: [epharm.inkar.kz](https://epharm.inkar.kz). Через один HTTPS-хост доступны админка (`/`), backend (`/api/*`) и медиа (`/s3/*`). Текущее состояние среды проверяется по [`/api/health`](https://epharm.inkar.kz/api/health), а открытые задачи и критерии приёмки — по [бэклогу](docs/BACKLOG.md) и [release checklist](docs/RELEASE-CHECKLIST.md). Наличие реализации или зелёных тестов не означает, что сценарий уже принят на реальной кассе.

## Что находится в репозитории

| Компонент                 | Где искать                                                       | Назначение                                                                                                                                         |
| ------------------------- | ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Backend                   | [`admin-panel/backend/`](admin-panel/backend/)                   | Kotlin/Spring Boot API, PostgreSQL/Flyway, Redis, S3, авторизация, рекомендации, обучение, заказы и интеграции.                                    |
| HQ-админка                | [`admin-panel/frontend/`](admin-panel/frontend/)                 | React/Vite: кампании и правила, аптеки, кассы, экраны, обучение, заказы и операционные разделы.                                                    |
| POSM                      | [`App/`](App/), [`Models/`](Models/), [`App.Tests/`](App.Tests/) | Windows/.NET 10 клиент Standard-N: рекомендации, клиентский экран, QR-задания мерчендайзинга, очередь заказов, heartbeat и подписанные обновления. |
| Мобильное приложение      | [`lib/`](lib/), [`android/`](android/), [`ios/`](ios/)           | Flutter-приложение фармацевта: кампании, обучение, чеки и баланс.                                                                                  |
| Витрина                   | [`storefront/`](storefront/)                                     | Next.js магазин и самовывоз; отдельный transactional outbox доставляет заказы в Epharm.                                                            |
| Интеграции и эксплуатация | [`ops/`](ops/), [`pim-etl/`](pim-etl/), [`tools/`](tools/)       | Мост заказов ACC, PIM ETL, мониторинг, резервное копирование, релизы и rollback.                                                                   |
| Документация              | [`docs/`](docs/)                                                 | [Карта документов](docs/README.md), архитектура, контракты, runbook и приёмка.                                                                     |

Medusa — внешний источник товаров, штрихкодов и аптек; её сервер не входит в этот репозиторий. Backend хранит локальный снимок каталога и продолжает отдавать последний полный снимок при временной ошибке источника. Поток интернет-заказов идёт отдельно: витрина/ACC → подписанный outbox → Epharm → касса POSM → статусы обратно в витрину. QR-задания мерчендайзинга поступают через отдельный необязательный bridge; его сбой не должен прерывать рекомендации. Подробности: [архитектура](docs/01-architecture.md), [заказы](docs/19-order-fulfillment.md), [POSM](docs/05-posm-client.md).

## Локальный запуск backend и админки

Нужны Docker, JDK 22, Node.js 24, npm и свободные порты `5433`, `6379`, `9000`, `9001`, `8080`, `5173`. Команды ниже выполняются из корня репозитория. Убедитесь, что `java -version` показывает JDK 22; абсолютный `JAVA_HOME` зависит от вашей машины.

```bash
docker compose up -d --wait postgres redis minio
docker compose run --rm minio-init
```

В двух отдельных терминалах:

```bash
cd admin-panel/backend
./gradlew bootRun
```

```bash
cd admin-panel/frontend
npm ci
npm run dev
```

Админка откроется на <http://localhost:5173>, backend — на <http://localhost:8080/api/health>, Swagger UI — на <http://localhost:8080/swagger-ui.html>. Профиль `dev` включён по умолчанию; локальные учётные записи и настройка среды описаны в [RUNBOOK](docs/RUNBOOK.md). Не используйте dev-аккаунты, фиксированный OTP или локальные ключи в production.

## Мобильное приложение и витрина

Для Flutter нужен SDK из [CI-конфигурации](.github/workflows/ci.yml) (сейчас 3.27.1). После `flutter pub get` приложение можно запустить против локального backend:

```bash
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://localhost:8080
```

Для Android-эмулятора вместо `localhost` используйте `10.0.2.2`. `USE_API=false` включает офлайн-демо, не production-поток. На публичном backend OTP создаёт и проверяет Daribar; dev-код допустим только при явно включённом `OTP_DEV_MODE=true` в локальной среде. Подробнее: [мобильный runbook](docs/04-mobile-app.md) и [OTP](docs/15-daribar-otp.md).

Витрина запускается отдельно из `storefront/` через `npm ci` и `npm run dev`; ей нужны собственные переменные окружения и источник каталога. См. [её README](storefront/README.md). POSM собирается и тестируется на Windows; конфигурация кассы и диагностика — в [Windows runbook](App/WINDOWS_RUNBOOK.md).

## Проверки перед PR

Запускайте проверки затронутых компонентов. Для полного release-кандидата используйте [release checklist](docs/RELEASE-CHECKLIST.md) и обязательный GitHub Actions check `P0 / merge gate` на точном head PR.

```bash
(cd admin-panel/backend && ./gradlew build)
(cd admin-panel/frontend && npm ci && npm run lint && npm test && npm run build)
(cd storefront && npm ci && npm run lint && npm test && npm run build)
flutter analyze lib test && flutter test
./tools/check-p0-config.sh
```

Отдельно CI проверяет браузерные E2E, POSM/.NET, PIM ETL, контракты ACC bridge, shell/Compose и dependency audit. Перед включением изменений в аптеках нужны целевые smoke-тесты и приёмка на реальном устройстве: тесты кода не подтверждают наличие товара в конкретной аптеке, корректность кассового скана или успешную выдачу заказа.

## Выпуск и безопасность

Production-развёртывание и rollback выполняются по [release/runbook](docs/20-reliability-and-release.md) и [инструкции эксплуатации](docs/06-deployment-and-ops.md), а не из локальной команды `docker compose up --build`. POSM-релизы подписываются и проверяются перед массовым обновлением. Секреты, device tokens, SSH-доступы, ключи подписи и production `.env` не должны попадать в Git, README, логи или артефакты сборки.

Главные незакрытые приёмочные вопросы перечислены в [бэклоге](docs/BACKLOG.md): промышленная iOS-подпись/TestFlight, реальные сценарии заказов на двух кассах, полный POSM/QR smoke и официальный источник фискального чека. Правила рекомендаций могут содержать до пяти вариантов замены и cross-sell; перед их публикацией проверяйте остатки по аптекам — текущая выдача не скрывает автоматически отсутствующие локально товары.
