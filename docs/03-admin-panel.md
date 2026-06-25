# Admin Console

Path: `admin-panel/frontend/`.

Stack:

- React 19;
- Vite 8;
- TypeScript 6;
- Tailwind 3;
- React Router v6;
- TanStack Query v5;
- Zustand v5;
- axios;
- lucide-react;
- Vitest, Testing Library, Playwright.

The admin console is a desktop-only HQ tool. Root minimum width is 1280px; mobile adaptation is not a
goal because mobile users have the Flutter app.

## Routes

Public:

- `/login`

Protected under `AppShell`:

- `/dashboard`
- `/promo`
- `/promo/:id`
- `/rules`
- `/screens`
- `/pharmacies`
- `/pharmacies/:id`
- `/pharmacists`
- `/reconcile`
- `/ai-exam`
- `/finance`
- `/lift`
- `/lms`
- `/settings`
- `/storefront`

`/` redirects to `/rules`. `/banners` redirects to `/screens` because banners are now a tab/panel in
the Screens section.

## Sections

| Section     | Current purpose                                                                                                   |
| ----------- | ----------------------------------------------------------------------------------------------------------------- |
| Dashboard   | HQ summary/KPI entrypoint.                                                                                        |
| Promo       | Product campaign CRUD, Medusa product picker, gallery/cover, tiers, dates, goals, campaign rules, grid/list view. |
| Rules       | Read-only/global rules view plus rule builders/components; campaign rules are edited from Promo.                  |
| Screens     | Connected cash desks, one broadcast video/media flow, banners panel.                                              |
| Pharmacies  | Chains/pharmacies, CRUD, detail page, real Medusa-derived pharmacy seed data.                                     |
| Pharmacists | Registry, block/unblock, status/balance data.                                                                     |
| Reconcile   | Receipt moderation queue, claimed promos, POS/Excel source columns, approve/reject.                               |
| AI Exam     | Question bank CRUD.                                                                                               |
| Finance     | Payout batches, generation, approval with finance/HQ role checks.                                                 |
| Lift        | Pilot/control analytics view.                                                                                     |
| LMS         | Course CRUD.                                                                                                      |
| Storefront  | Read-only Medusa catalog as seen through backend proxy.                                                           |
| Settings    | Language/timezone/session settings.                                                                               |

## State and Data

- `src/app/store.ts` owns UI/session state: auth user/tokens, period, language, sidebar state,
  command palette, role switcher, contract modal.
- `src/lib/api.ts` owns the axios singleton and token refresh. It de-duplicates refresh requests.
- TanStack Query is used for server state. Mutations invalidate focused query keys.
- Query cache is persisted to localStorage and cleared on logout.
- DTO types live in `src/lib/api-types.ts` and should mirror backend DTOs exactly.

## UI and Design

Current design source of truth is `admin-panel/design-tokens-admin.md`:

- coral/cream Claude-style palette;
- class names `brand-green-*` and `brand-blue-*` remain for compatibility, but values are coral;
- semantic success green remains only for approved/success states;
- Manrope for UI and JetBrains Mono/tabular numbers for dense numeric data;
- quiet cream canvas, dark warm sidebar, coral as accent/CTA.

Important layout rules:

- Use `PageHeader`, `SectionCard`, `Metric`, `SummaryBar`, tables, drawers, and modals from `src/ui`.
- Do not flood pages with KPI cards; dense pages prefer `SummaryBar`.
- No raw hex colors in production components unless the design-token file explicitly requires a one-off.
- All money values use full KZT formatting, never abbreviated millions.

## Auth

All routes except `/login` are protected by `RequireAuth`. The axios interceptor adds
`Authorization: Bearer <accessToken>` and performs refresh on 401. If refresh fails as auth failure,
the store is cleared and the user returns to login.

## Build and Tests

```bash
cd admin-panel/frontend
npm install
npm run dev
npm run lint
npm test
npm run build
npm run test:e2e
```

Playwright E2E expects backend and local infra to be running. Vite can be launched by Playwright's
webServer configuration.

## Historical References

`admin-panel/references/` is a historical JSX prototype and visual reference. Production code lives in
`admin-panel/frontend/src`. Use the references only to understand intent, not as current architecture.
