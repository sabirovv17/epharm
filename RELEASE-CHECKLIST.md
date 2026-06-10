# RELEASE-CHECKLIST — Epharm / PharmaPayV2

Честный срез готовности к прод-релизу. Приоритеты:
**🔴 P0** — блокирует релиз; **🟡 P1** — важно, сразу до/после; **🟢 P2** — потом.

> Обновлено: 2026-06-10 (после многоагентного deploy-readiness аудита: 69 находок,
> 17 блокеров, адверсариальная верификация). Источники правды по решениям —
> `admin-panel/claude-admin-notes.md`, `claude-notes.md`, `RUNBOOK.md`.
> owner: **code** = чинит разработчик · **ops** = нужен доступ к серверу/кассам · **decision** = решение.

---

## ✅ Закрыто в проходе deploy-hardening (2026-06-10)

**Mobile**

- **Белый экран на iOS** — отключён Impeller (`FLTEnableImpeller=false`, Skia). Приложение
  запускается и рисует UI на реальном iPhone (iOS 26). Проверено на устройстве.
- **Обработка ошибок входа** — формы phone/profile ловили сетевую ошибку в `try/finally`
  без `catch` → кнопка молча не работала. Добавлен `showErrorSnackBar` + глобальный
  zone-обработчик (логирует, не подменяет UI). Камера/галерея/launchUrl — в try/catch.
- **Прод baseUrl** — дефолт `https://api.epharm.kz` (был `localhost` → 100% нерабочее
  на телефоне). `build_all.sh` передаёт обязательные `--dart-define`.
- **Лишние permissions** убраны (микрофон/RECORD_AUDIO, сохранение в галерею) — частая
  причина отказа в App Store/Play.
- Реальные 522 аптеки, реальный каталог, persist токенов, cleartext/ATS dev-only.

**Backend**

- **Эскалация pharmacist→admin (P0)** — `SecurityConfig` разделён по authority:
  `/api/admin/**`→админ-роли, `/api/mobile/**`→PHARMACIST, `/actuator/**`→админ-роли.
- **Сегрегация выплат** — `@EnableMethodSecurity` + `@PreAuthorize` на approve/generate
  (FINANCE_REVIEWER|HQ_HEAD).
- **Bootstrap первого админа в проде** — `ProdBootstrap` (profile=prod) создаёт HQ_HEAD из
  `ADMIN_BOOTSTRAP_EMAIL/PASSWORD`, иначе в консоль не войти. + fail-fast на дефолтных
  `JWT_SECRET`/`POSM_DEVICE_KEY`.
- **`application-prod.yml`** — `OTP_DEV_MODE=false`, Swagger off, `include-message=never`.
- **OTP не пишется в логи** · **POSM device-key — constant-time** сравнение.
- `./gradlew build` SUCCESSFUL (юнит + Testcontainers + bootJar).

**Admin**

- **Прод-сборка чинена (P0)** — `npm run build` падал (variant + тесты в tsc) → exit 0.
- **ErrorBoundary** (вместо белого `#root` при краше) · **security-заголовки nginx**
  (X-Frame-Options/CSP/nosniff/Referrer/noindex).

**POSM (C#)**

- Авто-апдейтер: **sha256 обязателен** + **только https** (анти-RCE на кассе).
- Путь лога кассы — в env `EPHARM_LOG_PATH` (был хардкод).
- ⚠️ C# не собирался на macOS — **нужна Windows-сборка** (`dotnet build`) перед заливом на кассы.

---

## 1. 🔴 P0 — блокеры прод-релиза

### owner = code (осталось)

| #    | Дыра                                                                                                                                                          | Где                                                                                   | Что сделать                                                                                                                                                                                                                |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P0-6 | **Фото чеков (PII) в публично-читаемом MinIO-бакете** — `mc anonymous set download` + Caddy на `s3.epharm.kz` → чек скачивается по ссылке без аутентификации. | `docker-compose.prod.yml:75`, `ReconcileService.kt`, `S3MediaStorage.kt`, `Caddyfile` | Приватный бакет для чеков (доступ только через presigned-URL с TTL из backend) + отдельный публичный бакет для слайдов экранов. _(аудит: фактически P1 — ключи UUID, листинга нет; держим высоко из-за чувствительности)._ |

### owner = ops

| #    | Дыра                                             | Что сделать                                                                                                                                                                                                                                                   |
| ---- | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P0-A | **Прод-секреты `.env.prod`**                     | Сгенерировать `JWT_SECRET` (`openssl rand -base64 48`), `POSM_DEVICE_KEY` (`openssl rand -hex 24`), пароли БД/MinIO, `ADMIN_BOOTSTRAP_EMAIL/PASSWORD`, `OTP_DEV_MODE=false`. Без них прод-стек не стартует (есть `:?`-guard + `ProdBootstrap`).               |
| P0-B | **Ротация скомпрометированных доступов витрины** | SSH root `78.140.246.238` → ключи (`PasswordAuthentication no`), admin-пароли Medusa/PIM из `STOREFRONT-CREDENTIALS.md` (они светились в переписке). Чистка git-истории **НЕ нужна** — секреты туда не коммитились (проверено аудитом), нужна именно ротация. |
| P0-C | **Бэкап Postgres**                               | sidecar/cron `pg_dump \| gzip` → off-site/S3, retention 30д. **Проверить restore до запуска.** Вся финансовая БД на одном volume.                                                                                                                             |
| P0-D | **Развернуть прод-стек**                         | `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build`. TLS Caddy на `api/admin/s3.epharm.kz`.                                                                                                                                        |
| P0-E | **POSM на кассах** (для релиза «с кассами»)      | Собрать на Windows, поставить sidecar на кассы Стандарт-Н, задать `EPHARM_LOG_PATH`/`POSM_DEVICE_KEY`. Без этого «продажа→бонус» идёт через ручную сверку.                                                                                                    |

### Осознанно отложено (решение пользователя)

- **SMS-отправка** — оставлен dev-OTP `544544`. Для реального входа фармацевта нужен
  `MobizonSmsSender` + ключ. До этого прод-вход работает только при `OTP_DEV_MODE=true` (пилот).

---

## 2. 🟡 P1 — важно, сразу до/после релиза

**code**

- **RBAC-матрица полностью** — сейчас закрыта эскалация + finance approve; навесить
  `@PreAuthorize` на остальные мутации (block/unblock, rules, promo) по ролям. `[backend]`
- **Rate-limit** на `/admin/auth/login` и `/mobile/auth/sms/request` (bucket4j/Redis, 429).
- **9 фронт-тестов падают** (таймауты rules + типы фикстур `mkRule` без `card`) — починить
  фикстуры/таймауты (сборку НЕ блокирует — тесты исключены из `tsc`).
- **react-router** → `>=6.30.4` (2 moderate open-redirect).
- **Splash-экран iOS** брендировать (сейчас 1×1 белый LaunchImage).

**decision**

- **Single-instance backend** vs ShedLock на PayoutScheduler (при >1 реплике — задвоение
  выплат). Сейчас безопасно при 1 контейнере — зафиксировать в RUNBOOK как hard-constraint.
- **Medusa по plaintext HTTP** на голый IP `78.140.246.238:9000` — поднять TLS
  (`https://api.inkar.kz`); до этого firewall/allowlist; ротировать publishable key после.
- **JWT в localStorage** админки (XSS) — принять риск + строгий CSP/короткий TTL **или**
  refresh в httpOnly cookie.

**ops**

- **Консоль MinIO (9001)** опубликована на `0.0.0.0` → бинд `127.0.0.1` / VPN.
- **Sentry** (backend+mobile+admin) + **Hikari/JVM-лимиты** под RAM сервера.
- Сменить предсказуемые `POSTGRES_USER`/`MINIO_ROOT_USER=epharm` на неочевидные.

---

## 3. 🟢 P2 — hardening / качество / документация

- POSM_DEVICE_KEY per-device (сейчас общий); `/api/admin/dev/**` ограничить профилем dev;
  actuator/prometheus — ролью; Android release-подпись (не fallback на debug-keystore).
- Хранение реквизитов выплаты (маска карты last4+бренд) — сейчас карта только локально.
- Удалить дубль `epharm-posm/` + `.zip` (стейл-снимок, собирать из `App/`).
- Заказы/checkout в приложении — НЕ делаем (каталог read-only). OCR/ОФД — НЕ делаем
  (решение зафиксировано). i18n мобилки (kk) — пока ru-KZ. Версия `0.1.0`→`1.0.0` перед сторами.

---

## 4. Что входит в релиз (scope)

| Модуль                                    | Статус                                                                           |
| ----------------------------------------- | -------------------------------------------------------------------------------- |
| **M1** Приложение фармацевта (Flutter)    | ✅ код готов, запускается на iPhone; прод-блокеры — P0-6 (косвенно), SMS отложен |
| **M1** Админка HQ (React)                 | ✅ готова, прод-сборка зелёная                                                   |
| **M1** Backend (Kotlin/Spring)            | ✅ готов, build SUCCESSFUL; осталось P0-6 (бакет чеков) + ops-секреты            |
| **M2** POSM-кассы (C#)                    | ⚠️ код + анти-RCE фиксы есть, **нужна Windows-сборка + развёртывание на кассах** |
| **M3** Интернет-магазин (Medusa/inkar.kz) | 🚧 другая команда; мы только **читаем** каталог                                  |

**Решение по бонусам:** релиз «с кассами» — авто-цикл «продажа→бонус» включается при
развёртывании POSM (P0-E). До этого — фарма видит каталог/баланс + грузит чек на ручную
сверку, HQ начисляет бонус.

---

## 5. Definition of Done для деплоя

- [x] `npm run build` (admin) → exit 0
- [x] backend `./gradlew build` → SUCCESSFUL
- [x] `flutter analyze` чисто + 46 тестов
- [x] pharmacist-токен → 403 на `/api/admin/**` (тест)
- [x] релизный мобильный билд бьёт по `https://api.epharm.kz`
- [ ] первый admin создаётся `ProdBootstrap` из env (проверить на стейдже)
- [ ] чек скачивается только по presigned-URL (P0-6)
- [ ] restore Postgres проверен (P0-C)
- [ ] прод-стек поднят, TLS на 3 доменах (P0-D)
- [ ] POSM собран на Windows и стоит на кассах (P0-E)
