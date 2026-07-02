# Epharm Project Documentation

This folder is the maintained technical documentation for the current codebase. It is not deployed
to the server.

## Product

Epharm is a pharmacist-motivation system for Inkar/Ledex:

1. HQ creates product campaigns, replacement/cross-sell rules, banners, payout batches, screen media,
   courses, and moderation decisions in the web admin console.
2. Pharmacists use the mobile app to view campaigns, inspect product cards, upload receipt photos,
   and track receipt status and balance.
3. POSM clients installed on Standard-N cash desks read cash-desk logs, send scanned items to the
   backend, show recommendations to pharmacists, mirror receipt/video content on the customer screen,
   and report sales/heartbeat.
4. The backend reconciles accepted recommendations, POS sales, Excel imports, and mobile receipts
   into approved or manually reviewed bonuses.

## Active Environment

Current shared environment:

```text
https://epharm.78-140-246-238.sslip.io
```

It is one public host behind Caddy:

- `/api/*` -> backend;
- `/s3/*` -> MinIO;
- `/` -> admin frontend.

The `api/admin/s3.epharm.kz` split-domain model is a future/ops option, not the currently active
public endpoint unless `.env.prod` and `Caddyfile` are changed together.

## Docs Map

| File                        | Purpose                                                                       |
| --------------------------- | ----------------------------------------------------------------------------- |
| `00-project-map.md`         | Карта проекта: где лежит код/доки/важные файлы. НАЧИНАТЬ ОТСЮДА.              |
| `01-architecture.md`        | Current system architecture, runtime surfaces, business flows.                |
| `02-backend.md`             | Backend stack, domains, API map, security, integrations, test/build commands. |
| `03-admin-panel.md`         | Admin frontend routes, state, UI system, sections, tests.                     |
| `04-mobile-app.md`          | Flutter app architecture, active flows, API/mock switching, build notes.      |
| `05-posm-client.md`         | C#/WPF POSM client, log parsing, barcode matching, outbox, deployment.        |
| `06-deployment-and-ops.md`  | Docker/Caddy production stack, deploy, backup, operations, known risks.       |
| `07-database.md`            | Flyway migrations and domain tables.                                          |
| `RUNBOOK.md`                | Day-to-day local startup, reset, tests, and production commands.              |
| `DEV-ONBOARDING.md`         | Run mobile app on Android/iPhone against shared backend.                      |
| `RELEASE-CHECKLIST.md`      | Release blockers and hardening backlog.                                       |
| `BACKLOG.md`                | Текущий бэклог: доп-проверки + что осталось до завершения.                    |
| `claude-notes.md`           | Current mobile working memory.                                                |
| `STOREFRONT.md`             | Medusa storefront integration notes (untracked).                              |
| `STOREFRONT-CREDENTIALS.md` | Storefront/SSH credentials (untracked, NEVER commit).                         |

## Other Important Files

| File                                    | Purpose                                                         |
| --------------------------------------- | --------------------------------------------------------------- |
| `../CLAUDE.md`                          | Контракт для Claude: docs-first, карта, правила (автозагрузка). |
| `../README.md`                          | Short repo overview and quick start.                            |
| `../admin-panel/claude-admin-notes.md`  | Current backend/admin/POSM working memory.                      |
| `../admin-panel/design-tokens-admin.md` | Current admin design system.                                    |
| `../_reference/design-tokens.md`        | Current mobile design system.                                   |
| `../App/WINDOWS_RUNBOOK.md`             | POSM module runbook (module-local).                             |

## Documentation Rules

- Prefer these docs plus the two `claude*notes.md` files over historical handoff files.
- Treat `admin-panel/PLAN.md` and `_reference/design_handoff_pharmapay/*` as historical context unless
  a current doc explicitly points to them.
- Do not move or duplicate secrets from existing credential files.
- When changing behavior, update the relevant doc in the same work session.
