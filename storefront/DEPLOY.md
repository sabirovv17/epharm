# Деплой «Аптека со склада» (сайт + общая БД)

Сайт `inkar-shop` (Next.js 16) держит витрину (товары/цены из Medusa, серверно) и
**админку контента** (баннеры, быстрые ссылки, сторис, подборки). Контент хранится в
**общей БД (Postgres)** — её же читает мобильное приложение через `/api/content`.

> Бери **VPS / долгоживущий Node-хост**, не serverless: админка пишет картинки на диск
> (`public/uploads`). На Vercel это эфемерно — для serverless нужен S3-аналог (см. конец).

---

## Быстрый путь под твой сервер (Node + Nginx уже стоят, по IP, без Docker/домена)

> Сайт собран со `output: standalone` → запускаем `.next/standalone/server.js` под PM2, Nginx проксирует.
> По HTTP без домена — только для теста/soft-launch. Перед боевым запуском добавь домен + HTTPS (certbot, в конце).

**1. Код на сервер** (из Git Bash на Windows; замени USER@SERVER_IP):

```bash
rsync -avz --exclude node_modules --exclude .next --exclude .git \
  -e ssh /c/Users/vgrus/inkar-shop/ USER@SERVER_IP:/var/www/inkar-shop/
```

**2. Сборка** (на сервере):

```bash
cd /var/www/inkar-shop
npm ci && npm run build
# standalone не копирует статику сам — доносим вручную:
cp -r public .next/standalone/ && cp -r .next/static .next/standalone/.next/
```

**3. PM2 c переменными** — создай `ecosystem.config.js` в корне (секреты НЕ коммить):

```js
module.exports = {
  apps: [
    {
      name: 'inkar-shop',
      script: '.next/standalone/server.js',
      cwd: '/var/www/inkar-shop',
      env: {
        NODE_ENV: 'production',
        PORT: 3000,
        HOSTNAME: '127.0.0.1',
        MEDUSA_URL: 'http://78.140.246.238:9000',
        MEDUSA_PUBLISHABLE_KEY: '<set-in-secrets-env>',
        MEDUSA_SALES_CHANNEL: 'sc_01KRXGQFYXMJN3FD1WJ7S83WME',
        MEDUSA_REGION: 'reg_01KSBNEH2D4GVJN8EATK79WNSH',
        MEDUSA_ENABLED: 'true',
        ADMIN_TOKEN: 'СГЕНЕРИРУЙ: openssl rand -hex 24',
        CUSTOMER_AUTH_MODE: 'demo', // demo без SMS; для боевого SMS вернуть "sms"
        CUSTOMER_AUTH_SECRET: 'СГЕНЕРИРУЙ: openssl rand -hex 32',
        CHECKOUT_QUOTE_SECRET: 'СГЕНЕРИРУЙ ОТДЕЛЬНО: openssl rand -hex 32',
        CDP_HASH_SECRET: 'СГЕНЕРИРУЙ ДРУГОЙ: openssl rand -hex 32',
        VAPID_PUBLIC_KEY: '<npx web-push generate-vapid-keys --json>',
        VAPID_PRIVATE_KEY: '<из той же стабильной пары>',
        VAPID_SUBJECT: 'mailto:ops@quasar-it.kz',
        CATALOG_READ_SOURCE: 'postgres',
        DATABASE_URL: 'postgresql://...', // обязательна для общего контента/каталога/CDP
      },
    },
  ],
}
```

```bash
sudo npm i -g pm2
# Для запуска без Docker миграции применяются явно перед первым стартом и каждым деплоем.
DATABASE_URL='postgresql://...' npm run db:migrate
pm2 start ecosystem.config.js
pm2 save && pm2 startup     # автозапуск после ребута — выполни выведенную команду
pm2 logs inkar-shop         # поднялся без ошибок?
```

**4. Nginx** — готовый production-файл `deploy/nginx.conf` уже зафиксирован на
`apteka-demo.quasar-it.kz` и проксирует в `127.0.0.1:3000`:

```bash
sudo cp deploy/nginx.conf /etc/nginx/sites-available/inkar-shop
sudo ln -sf /etc/nginx/sites-available/inkar-shop /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

**5. Проверка:**

```bash
curl -I http://127.0.0.1:3000        # локально жив
curl -I http://SERVER_IP             # через Nginx
curl -s http://78.140.246.238:9000/health   # доступен ли Medusa с этого сервера
curl -fsS http://127.0.0.1:3000/api/health          # liveness, без внешних запросов
curl -fsS http://127.0.0.1:3000/api/health?deep=1   # readiness: Medusa + Postgres + миграции
```

Открой `http://SERVER_IP`. Каталог пуст → проверь `MEDUSA_PUBLISHABLE_KEY` и доступ к Medusa (шаг 5; если нет — allowlist по IP у Medusa, это к Александру).

**Передеплой:** rsync заново → `npm ci && npm run build` → `DATABASE_URL=... npm run db:migrate`
→ `cp -r public .next/standalone/ && cp -r .next/static .next/standalone/.next/`
→ `pm2 restart inkar-shop`. Если миграция не прошла, старый процесс не перезапускать.

---

_Ниже — альтернатива: полный путь через Docker Compose._

## Что нужно

- VPS (2 vCPU / 2–4 ГБ RAM хватит), Docker + docker compose.
- Домен, направленный A-записью на сервер.
- Доступ к **Postgres**: общая база Medusa, отдельный Postgres (есть в compose) или облачный.

## Шаги (Docker Compose)

1. **Скопировать проект на сервер** (git clone / scp папки `inkar-shop`).

2. **Создать `.env.production`** из примера и заполнить:

   ```bash
   cp .env.example .env.production
   nano .env.production
   ```

   - `ADMIN_TOKEN` — длинный случайный (`openssl rand -hex 24`). Это и код входа в `/admin`.
   - `SITE_DOMAIN=apteka-demo.quasar-it.kz` — официальный nginx-образ подставит его
     в `deploy/nginx.conf.template`; ручная правка конфига не нужна.
   - `CUSTOMER_AUTH_SECRET`, `CHECKOUT_QUOTE_SECRET`, `CDP_HASH_SECRET` — три разных
     стабильных значения из `openssl rand -hex 32`.
   - `VAPID_PUBLIC_KEY` + `VAPID_PRIVATE_KEY` — одна стабильная пара из
     `npx web-push generate-vapid-keys --json`; `VAPID_SUBJECT=mailto:ops@quasar-it.kz`.
   - `MEDUSA_*` — как в текущем `.env.local`.
   - `DATABASE_URL`:
     - **своя БД из compose:** `postgresql://inkar:ВАШ_ПАРОЛЬ@postgres:5432/inkar_cms`
       (тот же пароль задай переменной `POSTGRES_PASSWORD` в `.env.production`);
     - **общая база Medusa:** `postgresql://USER:PASS@HOST:5432/DBNAME` — и **удали**
       сервис `postgres` + `depends_on: postgres` из `docker-compose.yml`;
     - **облачная (Neon/Supabase):** строка с `?sslmode=require` — тоже удали локальный `postgres`.

3. **TLS-сертификаты** в `deploy/certs/` (`fullchain.pem` + `privkey.pem`).
   Проще всего разово через certbot:

   ```bash
   sudo certbot certonly --standalone -d apteka-demo.quasar-it.kz
   cp /etc/letsencrypt/live/apteka-demo.quasar-it.kz/{fullchain,privkey}.pem deploy/certs/
   ```

   `docker-compose.yml` монтирует шаблон nginx и подставляет только `SITE_DOMAIN`
   и `NGINX_UPSTREAM`, поэтому nginx-переменные `$host`/`$request_uri` не портятся.

4. **Запуск:**

   ```bash
   docker compose --env-file .env.production up -d --build
   ```

   Сайт поднимется за nginx на 80/443. Контейнер ждёт Postgres и применяет
   версионированные миграции до запуска Next.js. После первого старта заполнить
   локальный каталог canary, проверить и выполнить полный sync:

   ```bash
   docker compose --env-file .env.production exec -T web node scripts/sync-medusa-catalog.mjs --apply --full --limit 100
   docker compose --env-file .env.production exec -T web node scripts/sync-medusa-catalog.mjs --apply --full --mark-missing-inactive
   ```

   Только после успешного полного sync установить `CATALOG_READ_SOURCE=postgres`
   и пересоздать `web`.

5. **Проверка:**
   - `https://apteka-demo.quasar-it.kz/` — витрина;
   - `https://apteka-demo.quasar-it.kz/admin` — вход по `ADMIN_TOKEN`, меняешь контент → он в БД;
   - `https://apteka-demo.quasar-it.kz/api/health?deep=1` — readiness всех зависимостей.

## Мобильное приложение (после деплоя)

APK берёт **товары/цены** из Medusa уже сейчас. Чтобы он брал **контент админки** с боевого
домена — пересобрать:

```bash
flutter build apk --release \
  --dart-define=CONTENT_BASE=https://apteka-demo.quasar-it.kz \
  --dart-define=MEDUSA_URL=https://apteka-demo.quasar-it.kz/api/medusa \
  --dart-define=CUSTOMER_BASE=https://apteka-demo.quasar-it.kz \
  --dart-define=CHECKOUT_BASE=https://apteka-demo.quasar-it.kz \
  --dart-define=CUSTOMER_AUTH_MODE=demo \
  --dart-define=ONLINE_PAYMENTS_ENABLED=false
```

(Без этого на реальном телефоне покажет встроенные дефолты — зашит эмуляторный `10.0.2.2`.)

## Альтернатива без Docker (PM2 + nginx)

```bash
npm ci && npm run build
DATABASE_URL=... npm run db:migrate
DATABASE_URL=... ADMIN_TOKEN=... MEDUSA_URL=... CUSTOMER_AUTH_MODE=demo \
  pm2 start "node .next/standalone/server.js" --name inkar-shop
```

nginx — как в `deploy/nginx.conf`, `proxy_pass http://127.0.0.1:3000`.

## Systemd: liveness recovery и синхронизация каталога

Для установки готовых units (если приложение запущено как `inkar-shop.service`):

```bash
sudo install -d -m 0750 /etc/inkar-shop
sudo install -m 0640 .env.production /etc/inkar-shop/inkar-shop.env
sudo cp deploy/systemd/inkar-shop-health*.service deploy/systemd/inkar-shop-health.timer /etc/systemd/system/
sudo cp deploy/systemd/inkar-shop-catalog-sync.service deploy/systemd/inkar-shop-catalog-sync.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now inkar-shop-health.timer inkar-shop-catalog-sync.timer
```

`/api/health` — дешёвая liveness-проверка процесса; только она может вызвать
автоматический restart. `/api/health?deep=1` возвращает `503`, если Medusa/Postgres
недоступны или есть неприменённые миграции, но внешняя авария не создаёт restart-loop.
Таймер каталога выполняет безопасный полный sync раз в час; advisory lock в скрипте
не допускает пересечения с ручным запуском. Проверка:

```bash
systemctl list-timers 'inkar-shop-*'
sudo systemctl start inkar-shop-catalog-sync.service
journalctl -u inkar-shop-catalog-sync.service -n 100 --no-pager
curl -fsS 'http://127.0.0.1:3000/api/health?deep=1'
```

Если приложение живёт не в `/var/www/inkar-shop` или запускается не от `www-data`,
поменяй `WorkingDirectory`, `User` и `Group` в catalog-sync service до включения таймера.

## Масштабирование на несколько инстансов

Контент уже в общей БД ✓. Останется вынести загрузки `public/uploads` в объектное
хранилище (S3/MinIO) и заменить запись в `/api/upload` — отдельная небольшая доработка.
