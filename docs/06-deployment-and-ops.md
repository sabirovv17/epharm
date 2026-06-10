# Деплой и эксплуатация

## Сервер

| Параметр       | Значение                                 |
| -------------- | ---------------------------------------- |
| Внутреннее имя | `inkpim.inkar.kz`                        |
| IP             | `10.10.1.76` (внутренний, сеть INKAR)    |
| ОС             | Ubuntu 26.04 LTS server                  |
| Доступ         | SSH (порт 22), пользователь `adm-quasar` |
| Каталог деплоя | `/home/adm-quasar/epharm/`               |
| Docker         | `docker.io` + `docker-compose-v2`        |

Сервер **внутренний**: напрямую из интернета недоступен, только из сети INKAR / по корпоративному
VPN (Check Point, маршрутизирует `10.10/16`). Корп. DNS (`10.10.1.119/120`) резолвит
`inkpim.inkar.kz → 10.10.1.76`.

> Каталог `/home/adm-quasar/epharm` — **не git-репозиторий**: код заливается файлами. Перед
> деплоем сверяй конфиги с git по sha256 (см. «Чистый деплой» ниже) — был случай молчаливого
> недозалива Caddyfile, который ловится только проверкой хэша на диске.

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

## Caddy (`Caddyfile`)

| Блок                                          | Куда           | Что                                        |
| --------------------------------------------- | -------------- | ------------------------------------------ |
| `{$API_DOMAIN}` (api.epharm.kz)               | `backend:8080` | API мобилки/касс/админки, лимит тела 64МБ  |
| `{$ADMIN_DOMAIN}` (admin.epharm.kz)           | `frontend:80`  | публичная админка (по HTTPS)               |
| `http://{$INTERNAL_DOMAIN}` (inkpim.inkar.kz) | `frontend:80`  | **внутренняя админка по VPN** (plain HTTP) |
| `{$S3_DOMAIN}` (s3.epharm.kz)                 | `minio:9000`   | публичный бакет (фото чеков, слайды)       |

Caddy сам выпускает/продлевает TLS (Let's Encrypt) для публичных доменов; сертификаты — в
volume `caddy_data`.

> **Доступ только по ИМЕНИ.** Прямой IP (`http://10.10.1.76`) не работает: Caddy для bare-IP
> форсит self-signed TLS и редиректит 80→443 без валидного сертификата. На корп. Windows имя
> резолвит корп. DNS; на macOS + Check Point VPN (где `getaddrinfo` не видит `inkar.kz`) — строка
> `10.10.1.76 inkpim.inkar.kz` в `/etc/hosts`.

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

> `OTP_DEV_MODE=true` → код входа всегда `544544`. **Для публичного go-live выключить** (нужен
> реальный SMS-провайдер, сейчас не подключён).

## Первый запуск на сервере

```bash
cd /home/adm-quasar/epharm
# 1. .env.prod (один раз): сгенерировать секреты
bash tools/gen-prod-env.sh        # создаст .env.prod (НЕ коммитится)
#    затем вписать ADMIN_BOOTSTRAP_EMAIL/PASSWORD и домены
# 2. Поднять стек
sudo docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
# 3. ProdBootstrap создаст первого админа (HQ_HEAD) из ADMIN_BOOTSTRAP_*
```

## Чистый деплой обновления (из git, воспроизводимо)

Так как на сервере не git, заливаем ровно закоммиченные файлы архивом из git и пересобираем.

```bash
# ЛОКАЛЬНО: чистый архив HEAD только нужных путей
git archive --format=tar.gz -o /tmp/epharm-deploy.tar.gz HEAD \
  admin-panel/backend admin-panel/frontend docker-compose.prod.yml Caddyfile tools .env.prod.example
# (.env.prod НЕ в архиве — он gitignored, на сервере не перезатрётся)

# Передать на сервер (base64-через-ssh надёжнее scp), распаковать в каталог деплоя,
# затем на СЕРВЕРЕ:
cd /home/adm-quasar/epharm
sudo docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

**Проверка после деплоя** (с клиента в сети INKAR / по VPN):

```bash
curl http://inkpim.inkar.kz/api/health        # → {"status":"ok"}
curl -X POST http://inkpim.inkar.kz/api/admin/auth/login -d '{...}'  # → 401 на неверный пароль
```

Контейнеры: `sudo docker compose --env-file .env.prod -f docker-compose.prod.yml ps` — все `healthy`.

> Совет: после заливки конфигов проверяй `sha256sum` файла на диске против локального — это ловит
> молчаливые сбои передачи.

## Бэкапы

`tools/pg-backup.sh` — `pg_dump | gzip → backups/epharm-YYYYMMDD-HHMMSS.sql.gz`, retention 30 дней,
chmod 600. Ставится в root-cron:

```bash
sudo crontab -e
# 0 3 * * *  /home/adm-quasar/epharm/tools/pg-backup.sh >> /home/adm-quasar/epharm/backups/backup.log 2>&1
```

## Эксплуатация (шпаргалка)

```bash
cd /home/adm-quasar/epharm
EF="--env-file .env.prod -f docker-compose.prod.yml"
sudo docker compose $EF ps                       # статус
sudo docker compose $EF logs -f backend          # логи
sudo docker compose $EF restart caddy            # перезапуск одного сервиса
sudo docker compose $EF up -d --build backend    # пересборка backend
sudo docker logs epharm-caddy --tail 50          # ACME/TLS-логи Caddy

# MinIO-консоль (только с сервера): SSH-туннель с клиента
ssh -L 9001:127.0.0.1:9001 adm-quasar@10.10.1.76   # → http://localhost:9001
```

## CI (`.github/workflows/ci.yml`)

Триггеры: push в main + PR. Проверки:

- Frontend: ESLint · typecheck · Vitest · Vite build
- Backend: Gradle build · Gradle test (Testcontainers Postgres)
- Repo: commitlint (Conventional Commits)

## Доступ к админке по VPN (текущая схема)

1. Подключиться к корпоративному VPN INKAR (Check Point).
2. Открыть `http://inkpim.inkar.kz` (корп. Windows резолвит имя сам; на macOS — строка в `/etc/hosts`).
3. Вход: `admin@epharm.kz` (пароль из `.env.prod`).

Мобильное приложение и кассы для боевой работы должны ходить на **публичные** `api.epharm.kz` /
`s3.epharm.kz` из обычного интернета (не через VPN) — для этого нужны публичный DNS и проброс
портов (см. ниже).

## Что нужно для публичного go-live (служебка сетевикам INKAR)

1. **Публичные DNS A-записи** (зона `epharm.kz`) на внешний/NAT IP сервера:
   `api.epharm.kz`, `s3.epharm.kz` (обязательно для мобилки/касс), `admin.epharm.kz` (опционально —
   админку можно оставить только внутри по VPN).
2. **Проброс портов** (NAT + firewall) из интернета на `10.10.1.76`: `TCP 443` (основной) и
   `TCP 80` (для авто-выпуска Let's Encrypt + редирект на 443).
3. Как пробросят порты и пропишут DNS — Caddy выпустит сертификаты сам, без дополнительных действий.

## Открытые задачи безопасности перед публичным go-live

- **P0-6:** бакет чеков `epharm-receipts` сейчас публичный (анонимный download). Перед публичным
  выходом — сделать приватным + отдавать фото по presigned-URL.
- **Ротация секретов**, утёкших в переписку: SSH-пароль сервера, VPN-креды, прод-пароли в `.env.prod`.
- **Выключить dev-OTP** (`OTP_DEV_MODE=false`) и подключить реальный SMS-провайдер (Mobizon).
- Полный список — `RELEASE-CHECKLIST.md` в корне репозитория.
