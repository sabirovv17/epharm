# Admin / Backend / POSM Working Notes

Current working memory for backend, admin frontend, and POSM. Historical phase logs were removed from
this file; use git history for forensic detail.

## Product / Runtime

- Product name: Epharm.
- Active shared host: `https://epharm.78-140-246-238.sslip.io`.
- Current production shape: one Caddy site with path routing:
  - `/api/*` -> backend;
  - `/s3/*` -> MinIO;
  - `/` -> admin frontend.
- Future `api/admin/s3.epharm.kz` split is not active unless ops changes DNS, `.env.prod`, and
  `Caddyfile` together.

## Stack

Backend:

- Kotlin 2.0.21;
- Spring Boot 3.3.5;
- JVM 22;
- PostgreSQL 16;
- Flyway V001-V032;
- Redis;
- MinIO/S3;
- Testcontainers/JUnit/MockK.

Admin:

- React 19;
- Vite 8;
- TypeScript 6;
- Tailwind 3;
- TanStack Query;
- Zustand;
- axios;
- Vitest/Testing Library/Playwright.

POSM:

- C#/WPF/.NET 10;
- LibVLCSharp;
- SQLite outbox;
- Windows-only build.

## Current Backend Domains

`auth`, `mobile.auth`, `mobile.profile`, `mobile.catalog`, `mobile.promotions`, `mobile.receipts`,
`mobile.pharmacies`, `catalog`, `promo`, `rules`, `receipts`, `pharmacies`, `pharmacists`, `finance`,
`screens`, `banners`, `medusa`, `posm`, `appupdate`, `lms`, `ai_exam`, `lift`, `dashboard`, `shared`.

## Current Admin Sections

- Dashboard;
- Promo;
- Rules;
- Screens/Banners;
- Pharmacies;
- Pharmacists;
- Reconcile;
- AI Exam;
- Finance;
- Lift;
- LMS;
- Settings;
- Storefront.

## Current POSM Facts

- POSM implementation is C#/WPF, not Electron.
- Recommendation matching is barcode/EAN-first.
- `sku` / Standard-N `iPartID` is diagnostic only.
- Backend fallback matches normalized item name only if unique.
- Ambiguous barcode/name matches are skipped.
- POSM endpoints require `X-Posm-Key`.
- Screen updates use HTTP polling; SSE mode was cancelled.
- Cash-desk online count uses heartbeat.
- App auto-update uses `/api/posm/app/version` and SHA256-verified HTTPS zip.
- Показ рекомендации пингуется в backend (`POST .../{eventId}/shown` → `displayed_at`) через тот же
  outbox; продажа атрибутируется к показу по `session_id` (V032: `sold_at` / `sale_id` /
  `seconds_to_sale`). Аналитика — Dashboard «Журнал показов и продаж»
  (`GET /api/admin/dashboard/recommendations`): KPI конверсии/времени + единый лог из ДВУХ таблиц
  (`recommendation_events` показ + `pos_sales` продажа) с точным временем, авто-обновление 15с.
  Атрибуция — чистая аналитика, бонусы/выплаты не трогает.
- `pharmacistId` берётся из лога кассы (токен `kassir=`/`cashier=`), НЕ из `posm.json` (фармацевты
  посменно). На backend `pharmacistId` опционален (recommend+sales). Реальное поле Стандарт-Н по
  кассиру смапим, когда увидим настоящий `zkassa.log` ([[project_posm_log_format_unknown]]).
- Офлайн на кассе: sale/shown копятся в SQLite-outbox (WAL); при возврате сети flusher сбрасывает
  backoff (`MarkAllDue`) и досылает немедленно. Идемпотентно по saleId / eventId.

## Current Product Decisions

- OCR/OFD/QR receipt validation is removed.
- Receipt validation uses POS log, Excel import, mobile photo evidence, and manual moderation.
- Mobile receipt flow no longer manually selects pharmacy/promos.
- Real SMS provider is not connected; dev OTP may be used in pilot by decision.
- Storefront/PIM is external Medusa; this repo consumes it through backend proxy.
- Medusa images may be HTTP; browser/mobile should use backend media proxy.

## Design

Admin and mobile use coral/cream Claude-style palette.

- Compatibility token names `brand-green-*` and `brand-blue-*` remain.
- Values are coral.
- Semantic success remains green.
- Admin design source: `admin-panel/design-tokens-admin.md`.
- Mobile design source: `_reference/design-tokens.md`.

## Security / Release Risks

Current high-priority risks:

- receipt photos are public-readable in MinIO;
- storefront/PIM/SSH credentials in existing credential docs need rotation;
- SMS provider/rate limiting/RBAC hardening remain incomplete;
- POSM device key is shared rather than per-device;
- backup/restore must be tested for release.

Do not copy secrets from existing credential files into new docs, logs, issue bodies, or screenshots.

## Engineering Rules

- Reproduction-first where feasible.
- Minimal scoped changes.
- DTOs mirror between backend and frontend.
- Backend business errors use `AppException(ErrorCode, message, status)`.
- Controllers return DTOs, not entities.
- Applied Flyway migrations are immutable.
- Keep docs/notes updated after meaningful behavior changes.
- No raw UI hexes in production feature code; use tokens.

## Commands

Backend:

```bash
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew test
./gradlew build
```

Admin:

```bash
cd admin-panel/frontend
npm run lint
npm test
npm run build
```

Local stack:

```bash
docker compose up -d
```

Prod checks:

```bash
curl https://epharm.78-140-246-238.sslip.io/api/health
```

POSM local smoke:

```bash
curl -s -X POST http://localhost:8080/api/posm/recommend \
  -H 'X-Posm-Key: dev-posm-key' \
  -H 'Content-Type: application/json' \
  -d '{"pharmacistId":"u_smoke","pharmacyId":"ph_smoke","sessionId":"s1","cart":[{"barcode":"4603423004936","name":"Аквалор","qty":1}]}'
```

## Current Roadmap

See `admin-panel/PLAN.md` and `RELEASE-CHECKLIST.md`.
