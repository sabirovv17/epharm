# Epharm / PharmaPayV2

Epharm is the current product name. `PharmaPayV2`, `pharmacy`, and `PharmaPay` remain in code,
bundle ids, historical references, and design handoff files.

The repository is the working monorepo for the Ledex x Inkar pharmacist-motivation ecosystem:

- a Flutter mobile app for pharmacists;
- a Kotlin/Spring Boot backend;
- a React/Vite HQ admin console;
- a C#/WPF POSM client for Standard-N cash desks;
- integration with the external Medusa storefront/PIM used as a product and pharmacy source.

## Current Runtime

The shared demo environment is:

```text
https://epharm.78-140-246-238.sslip.io
```

Caddy serves one public host and routes by path:

- `/api/*` -> backend;
- `/s3/*` -> MinIO;
- `/` -> admin frontend.

The future `*.epharm.kz` domains are prepared in `.env.prod.example`, but the active public
environment is the `sslip.io` host unless explicitly changed in `.env.prod`.

## Modules

| Module | Path | Stack | Current state |
| --- | --- | --- | --- |
| Mobile app | `lib/`, `ios/`, `android/` | Flutter 3.27 / Dart 3.6, Riverpod, go_router, http, secure storage | Real API is the default. Offline mocks remain behind `--dart-define=USE_API=false`. |
| Backend | `admin-panel/backend/` | Kotlin 2.0.21, Spring Boot 3.3.5, JVM 22, PostgreSQL 16, Flyway, Redis, MinIO/S3 | Monolith with admin, mobile, POSM, Medusa proxy, media proxy, banners, payouts. Migrations V001-V030. |
| Admin console | `admin-panel/frontend/` | React 19, Vite 8, TypeScript 6, Tailwind 3, TanStack Query, Zustand, axios | 13 protected sections on real API. Promo has grid/list view, product gallery, banners live under Screens. |
| POSM client | `App/`, `Models/` | C# / WPF / .NET 10, LibVLCSharp, SQLite outbox | Windows-only client for Standard-N logs, barcode recommendations, customer display, heartbeat, auto-update. |
| Storefront/PIM | external Medusa | Medusa v2.15.2 | External source for catalog, images, barcodes, categories, and pharmacy stock locations. This repo only consumes it. |

## Repository Map

```text
PharmaPayV2/
├── lib/                     # Flutter mobile app
├── android/ ios/ macos/     # Flutter platform projects
├── assets/                  # fonts/images/icons for mobile
├── builds/                  # build_all.sh and review artifacts
├── admin-panel/
│   ├── backend/             # Kotlin/Spring backend
│   ├── frontend/            # React/Vite admin frontend
│   ├── references/          # historical JSX admin prototype
│   ├── design-tokens-admin.md
│   └── claude-admin-notes.md
├── App/                     # C#/WPF POSM app
├── Models/                  # shared POSM DTOs
├── docs/                    # maintained technical docs
├── _reference/              # mobile design references and historical handoff
├── tools/                   # prod env, backup, icon helpers
├── docker-compose.yml       # local Postgres/Redis/MinIO
├── docker-compose.prod.yml  # full production stack
├── Caddyfile                # current one-host path routing + internal VPN host
└── RUNBOOK.md               # day-to-day startup and troubleshooting
```

## Fast Local Start

Backend/admin local development uses Docker for infrastructure, `bootRun` for backend, and Vite for
frontend.

```bash
# from repo root
docker compose up -d

cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew bootRun

cd ../frontend
npm install
npm run dev
```

Useful URLs:

- backend health: `http://localhost:8080/api/health`;
- admin: `http://localhost:5173`;
- Swagger UI in dev: `http://localhost:8080/swagger-ui.html`;
- MinIO console: `http://localhost:9001`.

Dev admin users are seeded by the backend dev profile:

| Email | Password | Role |
| --- | --- | --- |
| `damir@jadran.com` | `damir2026` | Brand Manager |
| `aigerim@inkar.kz` | `aigerim2026` | Category Lead |
| `bauyrzhan@inkar.kz` | `bauyrzhan2026` | HQ Head |

## Mobile Start

Against the shared demo backend:

```bash
flutter run \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
```

Against a local backend:

```bash
# iOS simulator
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://localhost:8080

# Android emulator
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://10.0.2.2:8080

# offline demo
flutter run --dart-define=USE_API=false
```

OTP is `544544` while `OTP_DEV_MODE=true`. Real SMS is intentionally not wired yet.

## Quality Bar

Project working rule:

- reproduce bugs with a failing test first when feasible;
- fix root cause with the smallest scoped change;
- keep frontend DTOs aligned with backend DTOs;
- use `AppException(ErrorCode, message, status)` for backend business errors;
- update `claude-notes.md` or `admin-panel/claude-admin-notes.md` after non-trivial decisions;
- run the relevant suite before calling work done.

Common checks:

```bash
cd admin-panel/backend && ./gradlew test
cd admin-panel/frontend && npm test && npm run build
flutter analyze lib test && flutter test
```

## Security Notes

Secrets and live credentials currently remain in the files where they already exist, per project
practice for this workspace. Do not copy them into new docs, commits, logs, screenshots, or issue
bodies. The current release checklist still tracks rotation of storefront/PIM/SSH credentials and
privatization of receipt storage as important hardening work.

## Maintained Docs

- `RUNBOOK.md` - local startup, resets, tests, production stack operations.
- `docs/` - architecture, backend/admin/mobile/POSM/deployment/database.
- `DEV-ONBOARDING.md` - launching the mobile app on a real phone against the shared demo backend.
- `RELEASE-CHECKLIST.md` - current release blockers and hardening items.
- `admin-panel/claude-admin-notes.md` and `claude-notes.md` - working memory and latest decisions.
