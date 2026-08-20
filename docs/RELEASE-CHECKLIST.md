# Release Checklist

Current release-readiness snapshot for Epharm / PharmaPayV2.

Priorities:

- P0: blocks a safe production release;
- P1: important before/around launch;
- P2: hardening/backlog.

## Current Scope

| Area           | Status                                                                                      |
| -------------- | ------------------------------------------------------------------------------------------- |
| Backend        | Functional monolith, migrations V001-V030, prod stack builds.                               |
| Admin          | Functional HQ console on real API.                                                          |
| Mobile         | Functional Flutter app with real API default and offline mock fallback.                     |
| POSM           | C#/WPF implementation exists; production rollout needs Windows build/install per cash desk. |
| Storefront/PIM | External Medusa; this repo consumes catalog/images/barcodes/pharmacies only.                |

## P0

| Item                                           | Owner    | Status / action                                                                                                                                                                    |
| ---------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Receipt photos in public-readable MinIO bucket | code/ops | Move receipt photos behind authenticated/presigned URL access. Keep screen/broadcast media public or split buckets.                                                                |
| Production secrets                             | ops      | Ensure `.env.prod` has generated `JWT_SECRET`, `POSM_DEVICE_KEY`, DB/MinIO passwords, admin bootstrap credentials.                                                                 |
| Compromised storefront/PIM/SSH credentials     | ops      | Rotate credentials documented in existing credential files. Do not copy them into new docs.                                                                                        |
| Postgres backup and restore                    | ops      | Cron/off-site backup exists; restore has been tested.                                                                                                                              |
| POSM rollout for automated bonus loop          | ops      | Install scheduled tasks/watchdog and pharmacy/device config. Validate active-user extraction against the real Standard-N schema; do not bake a rotating employee into `posm.json`. |
| SMS provider                                   | code/ops | Daribar is integrated backend-to-backend. Keep `OTP_DEV_MODE=false`, verify live delivery, gateway latency/errors and resend limits before each mobile release.                    |

## P1

- Finish RBAC matrix on all admin mutations, not only finance/security-critical paths.
- Add rate limiting for admin login and mobile OTP request.
- Upgrade `react-router-dom` when practical; current dependency is `6.30.3`.
- Keep backend single-instance for payout scheduling or add distributed lock before scaling.
- Move Medusa behind HTTPS/allowlist when storefront ops are ready.
- Decide whether admin refresh stays in localStorage with strict CSP or moves to httpOnly cookie.
- Add Sentry or equivalent for backend/admin/mobile.
- Keep MinIO console localhost/VPN-only.
- Make POSM device keys per-device instead of one shared key.

## P2

- Brand iOS launch screen.
- Improve mobile localization beyond ru-KZ.
- Increase E2E coverage for finance, pharmacist creation, screen upload/broadcast.
- Add more Flutter widget tests for complex screens.
- Version mobile app to `1.0.0` before store release.
- Remove stale build snapshots and keep generated artifacts out of git.

## Definition of Done for a Release Candidate

Required checks:

```bash
cd admin-panel/backend && ./gradlew build
cd admin-panel/frontend && npm run lint && npm test && npm run build
flutter analyze lib test && flutter test
```

Runtime checks:

- `GET /api/health` returns 200 on the target host.
- Admin login works with bootstrap/admin credentials.
- Mobile login works by chosen OTP/SMS policy.
- Mobile promotions, catalog, banners, receipts, and `/me` work.
- Reconcile approve credits balance.
- POSM `/recommend`, `/sales`, `/heartbeat`, and `/playlists/active` work with the target device key.
- Caddy serves `/`, `/api/*`, and `/s3/*` correctly for the current domain layout.
- A fresh database can apply all Flyway migrations V001-V030.
- Backup and restore procedure has been tested.
