# Деплой и эксплуатация

## Сервер (текущий боевой)

Координаты проверены вживую **2026-06-18**.

| Параметр       | Значение                                                                           |
| -------------- | ---------------------------------------------------------------------------------- |
| Hostname       | `medusa-test`                                                                      |
| Публичный URL  | `https://epharm.78-140-246-238.sslip.io` (Caddy, TLS Let's Encrypt)                |
| IP             | `78.140.246.238` (публичный)                                                       |
| Доступ         | SSH по ключу: `ssh -i ~/.ssh/epharm_deploy root@78.140.246.238` (пароль не вводим) |
| Каталог деплоя | `/root/epharm` (docker compose project working_dir)                                |
| Docker         | `docker.io` + `docker compose v2`                                                  |

Сервер **публичный**: backend, веб-админка и MinIO отдаются наружу через Caddy на одном хосте
`epharm.78-140-246-238.sslip.io`. sslip.io резолвит этот хост прямо в IP сервера, поэтому Caddy
выпускает Let's Encrypt-сертификат без отдельного DNS. Домены `*.epharm.kz` — **будущие**: пока
не резолвятся на сервер (`api.epharm.kz` → health `000`), см. «Будущий публичный go-live» ниже.

> Каталог `/root/epharm` — **не git-репозиторий**: код заливается файлами (`git archive` → `scp`
> → `tar`). `.env.prod` и `docker-compose.prod.yml` лежат там же и при деплое **не
> перезатираются**. Перед деплоем сверяй конфиги с git по sha256 (см. «Чистый деплой» ниже) — был
> случай молчаливого недозалива Caddyfile, который ловится только проверкой хэша на диске.

## Прод-стек (`docker-compose.prod.yml`)

Одна команда поднимает всё ядро; у всех сервисов `restart: always` + healthcheck +
`depends_on: service_healthy` (самовосстановление после краша/перезагрузки).

| Сервис       | Образ                           | Порты                                   | Healthcheck                     |
| ------------ | ------------------------------- | --------------------------------------- | ------------------------------- |
| `postgres`   | `postgres:16-alpine`            | внутр.                                  | `pg_isready`                    |
| `redis`      | `redis:7-alpine`                | внутр.                                  | `redis-cli ping`                |
| `minio`      | `minio/minio:latest`            | `9000` внутр.; консоль `127.0.0.1:9001` | `/minio/health/live`            |
| `minio-init` | `minio/mc:latest`               | —                                       | создаёт бакет `epharm-receipts` |
| `backend`    | сборка `./admin-panel/backend`  | `8080` внутр.                           | через `depends_on`              |
| `frontend`   | сборка `./admin-panel/frontend` | `80` внутр.                             | `wget localhost`                |
| `caddy`      | `caddy:2-alpine`                | **`80`, `443`, `443/udp`**              | —                               |

- Backend: `mem_limit: 2g` + `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0` (heap ~1.3 ГБ).
- MinIO-консоль публикуется **только на `127.0.0.1`** сервера (доступ по SSH-туннелю).
- Caddy — единственная точка входа; backend/frontend/minio/postgres/redis наружу не публикуются.

## Caddy — единая точка входа (текущая боевая схема)

На боевом сервере Caddy слушает 80/443 на хосте `epharm.78-140-246-238.sslip.io` и
маршрутизирует **по путям** (проверено вживую 2026-06-18):

| Путь         | Куда           | Что                                                 |
| ------------ | -------------- | --------------------------------------------------- |
| `/api/*`     | `backend:8080` | API мобилки/касс/админки (`/api/health` → 200)      |
| `/s3/*`      | `minio:9000`   | публичный бакет `epharm-receipts` (фото чеков, APK) |
| `/` (прочее) | `frontend:80`  | веб-админка                                         |

Caddy сам выпускает/продлевает TLS (Let's Encrypt) — sslip.io резолвит хост в IP сервера,
поэтому ACME-проверка проходит без отдельного DNS. Сертификаты — в volume `caddy_data`.

> Шаблон `.env.prod.example` и таблица доменов в разделе «Будущий публичный go-live» описывают
> схему с отдельными субдоменами `api/admin/s3.epharm.kz` — она ещё **не активна** (домены не
> резолвятся на сервер). Боевой Caddyfile сейчас работает по путям на одном sslip.io-хосте.

## Переменные окружения (`.env.prod`)

Шаблон — `.env.prod.example`. Генерация безопасных секретов — `tools/gen-prod-env.sh`
(`JWT_SECRET`, `POSM_DEVICE_KEY`, пароли БД/MinIO, пароль первого админа; chmod 600).

| Группа       | Переменные                                                                                                   |
| ------------ | ------------------------------------------------------------------------------------------------------------ |
| Домены/TLS   | `API_DOMAIN`, `ADMIN_DOMAIN`, `S3_DOMAIN`, `ACME_EMAIL`                                                      |
| БД           | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`                                                          |
| MinIO/S3     | `MINIO_ROOT_USER/PASSWORD`, `MINIO_CONSOLE_PORT`, `S3_ENDPOINT`, `S3_PUBLIC_URL`, `S3_REGION`                |
| Бэкенд       | `JWT_SECRET`, `POSM_DEVICE_KEY`, `CORS_ALLOWED_ORIGINS`, `OTP_DEV_MODE`                                      |
| Первый админ | `ADMIN_BOOTSTRAP_EMAIL/PASSWORD/NAME/COMPANY`                                                                |
| Medusa       | `MEDUSA_ENABLED`, `MEDUSA_BASE_URL`, `MEDUSA_PUBLISHABLE_KEY`, `MEDUSA_SALES_CHANNEL_ID`, `MEDUSA_REGION_ID` |

> На боевом сервере `S3_PUBLIC_URL` = `https://epharm.78-140-246-238.sslip.io/s3` (а **не**
> `s3.epharm.kz` из примера). Дефолтные `*.epharm.kz` в `.env.prod.example` — заготовка под
> будущие домены.
> `OTP_DEV_MODE=true` → код входа всегда `544544`. **Для публичного go-live выключить** (нужен
> реальный SMS-провайдер, сейчас не подключён).

## Первый запуск на сервере

```bash
cd /root/epharm
# 1. .env.prod (один раз): сгенерировать секреты
bash tools/gen-prod-env.sh        # создаст .env.prod (НЕ коммитится)
#    затем вписать ADMIN_BOOTSTRAP_EMAIL/PASSWORD и домены
# 2. Поднять стек
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
# 3. ProdBootstrap создаст первого админа (HQ_HEAD) из ADMIN_BOOTSTRAP_*
```

## Чистый деплой обновления (из git, воспроизводимо)

Так как на сервере не git, заливаем ровно закоммиченные файлы архивом из git и пересобираем.

```bash
# ЛОКАЛЬНО: чистый архив HEAD только нужных путей
git archive --format=tar.gz -o /tmp/epharm-deploy.tar.gz HEAD \
  admin-panel/backend admin-panel/frontend docker-compose.prod.yml Caddyfile tools .env.prod.example
scp -i ~/.ssh/epharm_deploy /tmp/epharm-deploy.tar.gz root@78.140.246.238:/tmp/

# НА СЕРВЕРЕ: распаковать только исходники (не трогая infra-конфиг и .env.prod), пересобрать
cd /root/epharm
tar xzf /tmp/epharm-deploy.tar.gz admin-panel/backend admin-panel/frontend
bash tools/pg-backup.sh           # бэкап БД перед миграцией (см. «Бэкапы»)
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build backend frontend
```

**Проверка после деплоя** (с любого клиента, сервер публичный):

```bash
curl https://epharm.78-140-246-238.sslip.io/api/health        # → {"status":"ok"}
# 401 на неверный пароль:
curl -X POST https://epharm.78-140-246-238.sslip.io/api/admin/auth/login -d '{...}'
```

Контейнеры: `docker compose --env-file .env.prod -f docker-compose.prod.yml ps` — все `healthy`.

> Совет: после заливки конфигов проверяй `sha256sum` файла на диске против локального — это ловит
> молчаливые сбои передачи.

## Бэкапы

`tools/pg-backup.sh` — `pg_dump | gzip → backups/epharm-YYYYMMDD-HHMMSS.sql.gz`, retention 30 дней,
chmod 600. Ставится в root-cron:

```bash
crontab -e
# 0 3 * * *  /root/epharm/tools/pg-backup.sh >> /root/epharm/backups/backup.log 2>&1
```

## Эксплуатация (шпаргалка)

```bash
cd /root/epharm
EF="--env-file .env.prod -f docker-compose.prod.yml"
docker compose $EF ps                       # статус
docker compose $EF logs -f backend          # логи
docker compose $EF restart caddy            # перезапуск одного сервиса
docker compose $EF up -d --build backend    # пересборка backend
docker logs epharm-caddy --tail 50          # ACME/TLS-логи Caddy

# MinIO-консоль (только с сервера): SSH-туннель с клиента
ssh -i ~/.ssh/epharm_deploy -L 9001:127.0.0.1:9001 root@78.140.246.238   # → http://localhost:9001
```

## CI (`.github/workflows/ci.yml`)

Триггеры: push в main + PR. Проверки:

- Frontend: ESLint · typecheck · Vitest · Vite build
- Backend: Gradle build · Gradle test (Testcontainers Postgres)
- Repo: commitlint (Conventional Commits)

## Доступ к админке (текущая схема)

Админка публична за тем же Caddy-хостом — открыть `https://epharm.78-140-246-238.sslip.io/`,
вход `admin@epharm.kz` (пароль из `.env.prod`). VPN/корп-сеть для боевого sslip.io-сервера **не
нужны** — он доступен из обычного интернета.

## Будущий публичный go-live (домены `epharm.kz` / контур INKAR)

Заготовка под будущий переезд на собственные домены и/или внутренний контур INKAR. Пока **не
активна**: `*.epharm.kz` не резолвятся на сервер, боевой трафик идёт на sslip.io-хост (см. выше).

Планируемая схема субдоменов (отражена в `.env.prod.example` и Caddy-переменных):

| Блок                                | Куда           | Что                                       |
| ----------------------------------- | -------------- | ----------------------------------------- |
| `{$API_DOMAIN}` (api.epharm.kz)     | `backend:8080` | API мобилки/касс/админки, лимит тела 64МБ |
| `{$ADMIN_DOMAIN}` (admin.epharm.kz) | `frontend:80`  | публичная админка (по HTTPS)              |
| `{$S3_DOMAIN}` (s3.epharm.kz)       | `minio:9000`   | публичный бакет (фото чеков, слайды)      |

Что нужно сетевикам для go-live на `epharm.kz`:

1. **Публичные DNS A-записи** (зона `epharm.kz`): `api.epharm.kz`, `s3.epharm.kz` (обязательно для
   мобилки/касс), `admin.epharm.kz` (опционально).
2. **Проброс портов** (NAT + firewall) на сервер: `TCP 443` (основной) и `TCP 80` (авто-выпуск
   Let's Encrypt + редирект на 443).
3. Как пропишут DNS и пробросят порты — Caddy выпустит сертификаты сам, без дополнительных действий.

## Открытые задачи безопасности перед публичным go-live

- **P0-6:** бакет чеков `epharm-receipts` сейчас публичный (анонимный download). Перед публичным
  выходом — сделать приватным + отдавать фото по presigned-URL.
- **Ротация секретов**, утёкших в переписку: SSH-доступ сервера, прод-пароли в `.env.prod`.
- **Выключить dev-OTP** (`OTP_DEV_MODE=false`) и подключить реальный SMS-провайдер (Mobizon).
- Полный список — `RELEASE-CHECKLIST.md` в корне репозитория.
