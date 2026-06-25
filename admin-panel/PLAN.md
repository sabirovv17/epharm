# Admin / Backend / POSM Current Plan

This file used to contain the original implementation plan. The product is now well past that plan,
so this document is kept as the current roadmap and orientation for work inside `admin-panel/` and
POSM.

For latest day-to-day state, read `admin-panel/claude-admin-notes.md`.

## Current State

Completed and active:

- Kotlin/Spring backend monolith with Flyway migrations V001-V030.
- React/Vite admin console with real API integration.
- Mobile backend APIs for auth, profile, catalog, promotions, banners, receipts.
- Medusa proxy and image proxy.
- Promo campaign model: Medusa product, image gallery/override, dates, tiers, campaign goal, generated
  replacement/cross-sell rules.
- Admin promo grid/list view.
- Reconcile queue with POS/Excel/mobile/manual evidence.
- Finance payout batches and approval.
- Screens section simplified to connected-cash-desk count, broadcast media, and banners.
- POSM C#/WPF client integration: recommendations, outcomes, sale reports, heartbeat, playlist polling,
  app auto-update, CDP lookup/register.
- Barcode/EAN-first matching for POSM and sale reconciliation.
- Claude-style coral/cream redesign for admin and mobile.

Historical or cancelled:

- Electron POSM client is no longer the implementation.
- SSE screen modes were cancelled; POSM uses HTTP polling.
- OCR/OFD/QR receipt verification was removed.
- Manual mobile selection of pharmacy/promo in the receipt flow was replaced by backend/POSM matching plus
  optional claimed promo id.

## Current Priorities

### P0 / Release Hardening

- Private receipt-photo access with presigned URLs or authenticated proxy.
- Rotate storefront/PIM/SSH credentials that live in existing credential docs.
- Ensure production backup and restore.
- Decide pilot SMS policy: real provider or explicit `OTP_DEV_MODE=true`.
- Build and install POSM on real Windows cash desks if automated cash-desk bonus flow is in release scope.

### P1

- Complete RBAC checks on all admin mutations.
- Add rate limiting on login/OTP.
- Add observability/Sentry.
- Make POSM device keys per-device.
- Move Medusa behind TLS/allowlist when storefront ops is ready.
- Decide admin refresh-token storage model.

### P2

- More E2E and Flutter widget coverage.
- Mobile kk localization.
- Launch screen polish.
- App versioning and store-release hardening.

## Working Rules

- Backend packages: `kz.epharm.<domain>/{controller,service,repository,entity,dto,mapper}`.
- DTOs are not entities; controllers never return JPA entities directly.
- Backend business errors use `AppException(ErrorCode, message, status)`.
- Frontend types mirror backend DTOs in `frontend/src/lib/api-types.ts`.
- Admin design tokens come from `admin-panel/design-tokens-admin.md`.
- Mobile design tokens come from `_reference/design-tokens.md`.
- No raw secrets in new docs, logs, issues, or screenshots.
- Applied Flyway migrations are immutable; add a new migration instead.

## Verification Matrix

Backend:

```bash
cd admin-panel/backend
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

Mobile:

```bash
flutter analyze lib test
flutter test
```

POSM:

- build/run on Windows with .NET 10;
- smoke `/api/posm/recommend` with barcode;
- smoke `/api/posm/heartbeat`;
- verify `customerdisplay.log`.
