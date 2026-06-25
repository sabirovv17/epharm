# Backend

Path: `admin-panel/backend/`.

Stack:

- Kotlin 2.0.21;
- Spring Boot 3.3.5;
- JVM toolchain 22;
- Gradle 8.10.2 wrapper;
- PostgreSQL 16 + Flyway;
- Redis;
- MinIO/S3;
- Spring Security + JWT;
- Testcontainers + JUnit 5 + MockK.

The backend is a modular monolith under package `kz.epharm`.

## Domains

| Package | Responsibility |
| --- | --- |
| `auth` | Admin auth, JWT, refresh tokens, roles. |
| `mobile.auth` | Pharmacist OTP auth, registration, refresh/logout. |
| `mobile.profile` | `/api/mobile/me` profile/balance. |
| `mobile.catalog` | Public Medusa-backed product catalog for mobile. |
| `mobile.promotions` | Public active promo campaign feed. |
| `mobile.receipts` | Authenticated receipt upload/history. |
| `mobile.pharmacies` | Public active pharmacy list. |
| `catalog` | Internal/admin product master data used by rules. |
| `promo` | Campaigns, Medusa product snapshots, tiers, campaign-generated rules. |
| `rules` | Rules Engine admin CRUD and POSM matching logic. |
| `receipts` | Receipt storage, moderation, POS/Excel reconciliation, bonus crediting. |
| `pharmacies` | Chains and pharmacies. |
| `pharmacists` | Pharmacist registry, status, balance. |
| `finance` | Payout batches, items, approval, scheduler. |
| `screens` | Broadcast/screen media and playlists. |
| `banners` | Admin-managed mobile banners. |
| `medusa` | Storefront client/proxy and admin read-only catalog. |
| `posm` | POSM recommendations, outcomes, sales, playlists, heartbeat, CDP. |
| `appupdate` | POSM app release metadata and auto-update endpoint. |
| `lms`, `ai_exam`, `lift`, `dashboard` | Admin sections and reporting. |
| `shared` | Security, errors, media proxy, storage, validation, dev reset. |

## API Map

### Admin

- `POST /api/admin/auth/login|refresh|logout`, `GET /api/admin/auth/me`
- `/api/admin/dashboard/summary`
- `/api/admin/catalog/products`, `/brands`, `/mnn-groups`
- `/api/admin/rules/**`
- `/api/admin/promo/**`, including `/refresh-prices` and `/{id}/rules`
- `/api/admin/banners/**`
- `/api/admin/storefront/products`, `/products/{id}`
- `/api/admin/pharmacies/**`
- `/api/admin/pharmacists/**`
- `/api/admin/reconcile/**`, including `/submit` and `/import-excel`
- `/api/admin/payouts/**`
- `/api/admin/screens/**`, including `/connected` and `/broadcast`
- `/api/admin/app-releases/**`
- `/api/admin/lms/courses/**`
- `/api/admin/ai-exam/questions/**`
- `/api/admin/lift`

### Mobile

Public:

- `POST /api/mobile/auth/sms/request`
- `POST /api/mobile/auth/sms/verify`
- `POST /api/mobile/auth/register`
- `POST /api/mobile/auth/refresh`
- `GET /api/mobile/catalog/products`
- `GET /api/mobile/catalog/products/{id}`
- `GET /api/mobile/catalog/products/{id}/recommendations`
- `GET /api/mobile/catalog/recommendation-pools`
- `GET /api/mobile/catalog/categories`
- `GET /api/mobile/promotions`
- `GET /api/mobile/pharmacies`
- `GET /api/mobile/banners`

Authenticated pharmacist:

- `GET /api/mobile/auth/me`
- `POST /api/mobile/auth/logout`
- `GET /api/mobile/me`
- `GET /api/mobile/receipts`
- `POST /api/mobile/receipts` multipart.

### POSM

All POSM endpoints require `X-Posm-Key`.

- `POST /api/posm/recommend`
- `POST /api/posm/recommendations/{eventId}/outcome`
- `POST /api/posm/sales`
- `GET /api/posm/playlists/active?pharmacyId=...`
- `GET /api/posm/app/version`
- `POST /api/posm/heartbeat`
- `POST /api/posm/cdp/lookup`
- `POST /api/posm/cdp/register`

### Shared Public

- `GET /api/health`
- `GET /api/media/img?u=<medusa-http-image-url>`

The media proxy only allows the configured Medusa authority and exists to avoid browser mixed-content
blocking when the admin/mobile web views are served over HTTPS.

## Security Model

- Admin auth uses bcrypt passwords, access JWT, hashed refresh tokens, and admin roles.
- Mobile auth uses OTP and separate mobile refresh tokens.
- POSM uses a device key; comparison is constant-time.
- `GlobalExceptionHandler` returns JSON `{code,message,fields?}` instead of stack traces.
- Business errors should be `AppException(ErrorCode, message, HttpStatus)`.
- Receipt/photo bucket access is still a hardening topic: current MinIO bucket is public-readable.

## Medusa Integration

Backend is the only consumer of Medusa from this repo. It fetches:

- product list/detail/category data;
- images, barcodes, MNN/ATC/rx metadata;
- product recommendation data;
- pharmacy stock locations exported into `seed/pharmacies.json`.

Mobile/admin receive normalized DTOs from this backend, not raw Medusa responses.

Important behavior:

- `metadata` placeholders such as `-`, `_`, `none`, `n/a`, `н/д` are treated as empty.
- Prices may be missing in Medusa; UI must degrade to "Цена в аптеке".
- Product images may be HTTP; UI should display them through `/api/media/img`.

## POSM Matching

Barcode is the primary key for cash-desk recommendation matching.

`CartItemDto` can include:

- `barcode`/EAN - primary matching key;
- `name` - fallback matching key after normalization;
- `sku` - Standard-N internal `iPartID`, diagnostic only;
- `qty`.

Ambiguous barcode or normalized-name matches are logged and skipped. The system prefers not showing a
recommendation over showing the wrong one.

## Commands

```bash
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home

./gradlew bootRun
./gradlew test
./gradlew build
```

Local dev needs the root `docker compose up -d` infrastructure.
