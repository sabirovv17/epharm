# RUNBOOK — запуск и перезапуск Epharm Admin Console

Как поднять / перезапустить админ-панель локально: **инфраструктура (Docker) → backend (Spring Boot) → frontend (Vite)**. Плюс типовые проблемы и E2E.

> Все пути — от корня репозитория `PharmaPayV2/`.

---

## 0. Предусловия (один раз)

- **Docker Desktop** запущен (для Postgres / Redis / MinIO).
- **JDK 22 (Temurin)** для backend. JAVA_HOME:
  ```bash
  export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
  ```
- **Node 22+** для frontend.
- Порты свободны: **5433** (Postgres), **6379** (Redis), **9000/9001** (MinIO),
  **8080** (backend), **5173** (frontend).

---

## 1. Инфраструктура — Docker

```bash
# Поднять Postgres:5433 + Redis:6379 + MinIO:9000-9001 (в фоне)
docker compose up -d

# Проверить, что всё healthy
docker compose ps
docker exec epharm-postgres pg_isready -U epharm -d epharm   # → accepting connections

# Логи (если что-то не стартует)
docker compose logs -f postgres
```

Остановка / чистый старт:

```bash
docker compose stop          # остановить, данные сохранить
docker compose down          # остановить + удалить контейнеры (volume сохраняется)
docker compose down -v       # ⚠️ + удалить volume (полная очистка БД — Flyway пересоздаст схему)
```

> **Gotcha:** системный Postgres часто занимает 5432 — поэтому маппинг **5433:5432**.
> `application-dev.yml` ходит на `localhost:5433`.

---

## 2. Backend — Spring Boot (Kotlin)

```bash
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home

./gradlew bootRun            # profile=dev, http://localhost:8080
```

При старте: Flyway применяет миграции `V001..V0xx`, `DevDataSeeder` (profile=dev)
идемпотентно засевает demo-данные (3 админа, 13 товаров, 6 правил, 5 промо,
8 сетей × аптеки, курсы, экраны, вопросы).

Проверка готовности:

```bash
curl http://localhost:8080/api/health      # → {"status":"ok",...}
```

Перезапуск backend (после изменений в Kotlin):

```bash
# Останови текущий (Ctrl+C в его терминале) ИЛИ убей по порту:
lsof -ti tcp:8080 | xargs kill -9
# Запусти снова:
./gradlew bootRun
```

Полезное:

```bash
./gradlew test               # все тесты (JUnit + Testcontainers Postgres)
./gradlew build -x test      # только компиляция
```

> **JWT-секрет фиксированный** (`application.yml`) → перезапуск backend НЕ инвалидирует
> уже выданные токены. Dev-OTP для мобилки — `544544`.

### Dev-логины админки

| Email                | Пароль          | Роль                                            |
| -------------------- | --------------- | ----------------------------------------------- |
| `damir@jadran.com`   | `damir2026`     | Brand Manager (Jadran) — виден виджет контракта |
| `aigerim@inkar.kz`   | `aigerim2026`   | Category Lead (Inkar)                           |
| `bauyrzhan@inkar.kz` | `bauyrzhan2026` | HQ Head (Inkar)                                 |

### Сброс dev-данных к seed-базису (profile=dev)

```bash
curl -X POST http://localhost:8080/api/admin/dev/reset
# → {"status":"reseeded","products":13,"rules":6,"promos":5,"pharmacies":64}
```

Возвращает БД к чистой фикстуре (админы сохраняются). Используется E2E
(Playwright globalSetup + перед каждым UI-тестом). Эндпоинта НЕТ в prod.

---

## 3. Frontend — Vite (React)

```bash
cd admin-panel/frontend
npm install                  # один раз / после изменения зависимостей

npm run dev                  # http://localhost:5173 (ходит на backend :8080)
```

Перезапуск frontend обычно не нужен — Vite HMR подхватывает изменения. Полный
рестарт: Ctrl+C → `npm run dev`.

Прочее:

```bash
npm run build                # прод-сборка в dist/
npx tsc --noEmit             # проверка типов
npm test                     # Vitest (unit + integration)
```

---

## 4. Полный цикл «с нуля» (копипаст)

```bash
# 1) Инфра
cd /Users/amir/Desktop/work/pharma/PharmaPayV2
docker compose up -d

# 2) Backend (отдельный терминал)
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew bootRun
#   дождись: curl http://localhost:8080/api/health → ok

# 3) Frontend (ещё один терминал)
cd admin-panel/frontend
npm run dev
#   открой http://localhost:5173 → войди как damir@jadran.com / damir2026
```

Порядок остановки обратный: Ctrl+C frontend → Ctrl+C backend → `docker compose stop`.

---

## 4.1. Как загрузить видео на экраны (ТЗ §3.3)

1. Войди в админку → раздел **«Управление экранами»**.
2. Кнопка **«Загрузить слайд»** (справа вверху) → выбери видео (mp4/webm) или картинку,
   введи название + длительность (сек) → **Загрузить**. Файл уходит в MinIO, слайд
   появляется в «Библиотеке слайдов».
3. Кнопка **«Новый плейлист»** → задай имя → создать.
4. В «Библиотеке слайдов» у каждого слайда — выпадающий список **«В плейлист»**: выбери
   нужный плейлист (слайд привяжется, длительность плейлиста пересчитается).
5. В таблице «Активные плейлисты» нажми **«Активировать»** — плейлист в ротации (статус «Играет»).

> Лимит файла — 60 МБ. Видео хранятся в MinIO bucket `epharm-receipts` (префикс `screens/`),
> доступны по прямому URL (можно открыть в браузере). Назначение плейлистов на конкретные
> аптеки + расписание + показ на втором мониторе — Этап 5 (POSM).

## 4.2. Сверка чеков (ТЗ §3.5)

Раздел **«Сверка чеков»** показывает очередь чеков (демо-данные сидятся при старте):

- **Метрики** сверху: в очереди / авто-одобрено / на модерации / анти-фрод.
- **Табы:** В очереди (ручная модерация) · На модерации (анти-фрод) · Одобрены · Отклонены.
- По каждому чеку: SKU, ссылка на фото, фармацевт+аптека, сумма vs ожидаемая,
  столбцы **«Логи»**/**«Эксель»** (галочки источников), статус. Две галочки → авто-одобрение;
  одна/ноль → ручная проверка. Для pending/moderation/flagged — кнопки **«Одобрить»**
  (→ бонус начисляется на баланс фармацевта) и **«Отклонить»** (спросит причину).

Проверить flow через API (dev):

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/admin/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"damir@jadran.com","password":"damir2026"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["tokens"]["accessToken"])')
# Загрузить чек (фото) от имени фармацевта u_2 с выбранной аптекой:
curl -X POST localhost:8080/api/admin/reconcile/submit -H "Authorization: Bearer $TOKEN" \
  -F "pharmacistId=u_2" -F "file=@receipt.jpg" \
  -F "pharmacyId=ph_2" -F "pharmacyName=Аптека на Абая 10"
# Очередь + сводка:
curl -s "localhost:8080/api/admin/reconcile?status=pending" -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/admin/reconcile/summary -H "Authorization: Bearer $TOKEN"
```

> OCR/ОФД нет (и не будет): источники сверки — ТОЛЬКО лог Стандарт-Н (программа на C#) +
> Excel-выгрузка + ручная модерация. Фото — доказательство для модератора, автоматически
> не распознаётся. pending_bonus сейчас из seed (вживую создаёт POSM, Этап 5). Загрузка
> чека из приложения фармацевта — `/api/mobile/receipts` (тот же `submitReceipt`).

## 5. E2E (Playwright)

Требует поднятые **docker + backend (profile=dev) + frontend**. Frontend
Playwright поднимает сам (webServer), backend — вручную (см. п.2).

```bash
cd admin-panel/frontend
npx playwright test                 # весь набор (~3 мин, 105 passed / 1 skipped)
npx playwright test e2e/rules.spec.ts   # один файл
npx playwright show-report          # HTML-отчёт последнего прогона
```

> globalSetup и фикстуры дергают `POST /api/admin/dev/reset` → каждый прогон
> детерминирован. Запускать строго из `admin-panel/frontend/` (иначе конфиг не
> найдётся). 1 skipped — Bug Q (route.abort timing), осознанно отключён.

---

## 6. Частые проблемы

| Симптом                                                      | Причина → решение                                                                                                        |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| `bootRun` падает на старте, `Connection refused :5433`       | Postgres не поднят → `docker compose up -d`, дождись healthy                                                             |
| Порт 8080 занят                                              | `lsof -ti tcp:8080 \| xargs kill -9`                                                                                     |
| Порт 5173 занят                                              | `lsof -ti tcp:5173 \| xargs kill -9`                                                                                     |
| Порт 5432/5433 занят системным PG                            | используем 5433; если занят и он — поменяй маппинг в `docker-compose.yml`                                                |
| В админке «Backend недоступен» / «нет доступа» после простоя | access-токен жив 15 мин; обнови страницу — axios сделает refresh (refresh живёт 30 дней). Если не помогло — перелогинься |
| Данные «поехали» после ручных правок / E2E                   | `curl -X POST http://localhost:8080/api/admin/dev/reset`                                                                 |
| Flyway `checksum mismatch` после правки старой миграции      | НЕ редактируй применённые миграции; для чистого старта `docker compose down -v` → `bootRun`                              |
| Playwright «No tests found»                                  | запускай из `admin-panel/frontend/`, не из корня                                                                         |
| `JAVA_HOME` не та версия                                     | экспортни Temurin 22 (см. п.0); проверь `java -version`                                                                  |

---

## 7. Карта портов и сервисов

| Сервис              | URL / порт                              | Запуск                 |
| ------------------- | --------------------------------------- | ---------------------- |
| Postgres            | `localhost:5433`                        | `docker compose up -d` |
| Redis               | `localhost:6379`                        | docker                 |
| MinIO API / Console | `localhost:9000` / `:9001`              | docker                 |
| Backend (Spring)    | `http://localhost:8080`                 | `./gradlew bootRun`    |
| Backend health      | `http://localhost:8080/api/health`      | —                      |
| Swagger UI          | `http://localhost:8080/swagger-ui.html` | —                      |
| Frontend (Vite)     | `http://localhost:5173`                 | `npm run dev`          |
