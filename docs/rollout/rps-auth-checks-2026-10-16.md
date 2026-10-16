# Проверки входа РПС перед выпуском

Снимок проверок перед слиянием PR, не подтверждение массовой установки на кассы.
Начальная база — `aef761c`; перед повторным CI включён main `c099af2` с ограничением
retention резервных копий из параллельной задачи. Код редактора рекомендаций,
matcher и текущие акции не переписывались.

## Автоматические проверки

| Проверка | Результат |
| --- | --- |
| Полный backend | 592/592, без пропусков и ошибок |
| Mobile auth и staff auth | 73/73 в отдельном прогоне |
| Staff HTTP/PostgreSQL | 15 integration tests и отдельный disabled-capability test |
| POSM portable core | 187/187 |
| Windows-target WPF build | Успех, 0 предупреждений и ошибок; не проверка настоящего рабочего стола |
| Админка Vitest | 514/514, 49 файлов |
| Админка lint и TypeScript/Vite production build | PASS |
| Backend bootJar и merchandising artifact guard | PASS |
| Контракты archive two-image, rollback и backend-only | PASS |
| Независимая проверка OTP, гонок, V061, privacy и UI validation | Блокирующих замечаний нет |

Backend проверяет ограничения попыток, device/pharmacy/account/purpose binding,
повторное использование OTP, конкурирующие запросы, reset/activation и отзыв при
смене пароля, статуса, аптеки или ключа кассы. Регрессии отдельно воспроизводят
неопределённый ответ SMS-провайдера после отправки и гонку старой mobile activation.

Локальный Node 26 запущен с `NODE_OPTIONS=--no-experimental-webstorage`, поскольку
его native localStorage отличается от CI. CI закреплён на Node 24.15.0; код
приложения и learner-тесты для обхода этой разницы не менялись.

## Проверка копии серверной БД и отката

16 октября 2026 года в 10:00 UTC завершена свежая зашифрованная off-site копия
PostgreSQL/MinIO. Изолированный restore-test завершён в 10:02:06 UTC: 64 таблицы,
60 миграций, восстановлены MinIO buckets. Production volumes не подключались.

Новый JAR запущен в изолированной сети на восстановленном PostgreSQL 16, без
production credentials и внешних интеграций. V061 успешно применилась. Затем на
той же forward schema запущен именно прежний runtime image `epharm/backend:v0.1.36`.
Оба `/api/health` доступны; в копии сохранились 14 кампаний, 28 правил и 5 сотрудников.
Это проверка совместимости приложения со схемой, не down migration.

SHA-256 проверенного JAR:
`ddfa6441d1ea9cf5f4f01085870615ab5e11b8f447973f1395ddcd343682ecc5`.
Защищённые evidence находятся на сервере в
`epharm-backend-only-backups/rps-schema.qCqOKpVG`; dump и временные ключи туда
не включаются в Git или публичные артефакты.

Первый запуск выявил несоответствие тестовой среды: прежний образ использует
Java 21, а main/JAR — Java 22. Проверка повторена на Java 22 из Dockerfile main;
прежний образ для самого rollback-теста оставлен без изменений.
После тестов удалены только созданные ими временные контейнеры/volumes.
Рабочие данные и backup artifacts сохранены.

## Оставшиеся релизные ворота

Перед переключением нужны успешные CI/CodeQL текущего PR и точного merged main,
финальные immutable Docker images из свежего исходного архива, проверка built admin
image, новый rollback bundle и транзакционный deploy. POSM-кандидат берётся из
Windows CI того же merged commit и подписывается существующим pinned P-256 ключом.

На сервере подтверждены `OTP_PROVIDER=daribar`, `OTP_DEV_MODE=false` и совпадение
публичного update trust anchor с offline signing key. Настоящая доставка SMS,
частота Standard-N HEARTBEATS, видимость окна и фокус сканера требуют ручного
пилота по [инструкции](rps-auth-test.md). До него fleet current остаётся 1.0.68.

Отдельный старый Sentry source-map workflow не настроен: GitHub secrets отсутствуют.
Это не считается успешной проверкой Sentry и не заменяет обязательный CI/P0 gate.
Образы админки не публикуют source maps без их приватной загрузки.
