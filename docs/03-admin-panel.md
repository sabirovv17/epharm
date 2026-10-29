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

The local unified-admin candidate groups the existing routes into seven modules: Analytics;
Pharmacist motivation; Training; Merchandising; Pharmacies and products; Pharmacists; Management.
Management stays at the bottom of the sidebar. Grouping does not change canonical URLs or grant
permissions. The command palette uses the same available destinations, including every LMS tab.
The separate `/learn` cabinet and its personal session are unchanged.

The native `/merch/inventory` screen reads branch equipment and a six-month availability calendar.
It is shown only after a fresh `/api/admin/access/me` response explicitly grants
`canViewMerchInventory`; a legacy HQ or regional role alone is insufficient. A regional manager
with that capability keeps the existing training boundary and gains this Merch screen only.
Manufacturer booking and pharmacist task administration are marked **not connected** until their
native flows are integrated; the inventory screen is not a substitute for those flows.

The inventory screen paginates authorized branches and uses the native `/api/admin/merch/branches`
API. Block/unblock requires selected shelf IDs, an explicit month range, and confirmation.
Server-owned `canManage`, `canEdit`, and protected-placement checks remain authoritative. Failed
writes are not retried automatically. Withheld prices render as `—`, never as a zero price.
No inventory GET creates bookings or expires existing reservations.

Access Management distinguishes `PHARMACY` from `MERCH_BRANCH` permission scopes. The
**Аптеки мерча** selector stores `allMerchBranches/merchBranchIds` independently from
`allPharmacies/pharmacyIds`; the two identity domains are never guessed or combined. An empty
selection gives no access. Per-action delegation limits, role ceilings, CAS version and fresh
password confirmation are retained. Capabilities and inventory projections are not persisted in
the offline query cache.

Public:

- `/login`
- `/learn`, `/learn/recommendations`, `/learn/certificates`, `/learn/profile` and
  course links — a separate pharmacist cabinet using its own personal session, not
  an HQ session. The Recommendations button stays in this cabinet and lists active
  authored groups/offers from `/api/mobile/me/recommendations`; the home balance
  comes from `/api/mobile/me/balance`. Neither read creates a sale, impression or reward.
  Authentication/availability errors are explicit, not disguised as a zero balance or
  empty feed. Sign-out clears personal data and rejects late responses from that session.
- `/learn/merch/claim` — mobile-friendly pharmacist identity confirmation for a CRM task; it uses
  pharmacist authentication, not the HQ admin session, and does not grant access to the admin shell.

Protected under `AppShell`:

- `/dashboard`
- `/dashboard/reports` — HQ/SYSTEM_ADMIN PDF export, linked from Dashboard
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

`/` redirects by role: business roles go to `/rules`, training roles go to `/lms`. `/banners`
redirects to `/screens` because banners are now a tab/panel in the Screens section.

## Sections

| Section     | Current purpose                                                                                                                                                                                  |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Dashboard   | HQ summary/KPI entrypoint and paginated 90-day journal of recommendation shows and POS sales.                                                                                                    |
| Promo       | Product campaign CRUD, Medusa product picker, gallery/cover, tiers, dates, goals, campaign rules, grid/list view.                                                                                |
| Rules       | Read-only/global rules view plus rule builders/components; campaign rules are edited from Promo.                                                                                                 |
| Screens     | Connected cash desks, Excel inventory of POSM/client screens, 12-slot default/targeted playlists, banners.                                                                                       |
| Pharmacies  | Chains/pharmacies, CRUD, detail page, real Medusa-derived pharmacy seed data.                                                                                                                    |
| Pharmacists | Registry, pending-profile activation with pharmacy assignment, block/unblock, status/balance data.                                                                                               |
| Reconcile   | Receipt moderation queue, claimed promos, POS/Excel source columns, approve/reject.                                                                                                              |
| AI Exam     | Question bank CRUD.                                                                                                                                                                              |
| Finance     | Payout batches, generation, approval with finance/HQ role checks.                                                                                                                                |
| Lift        | Pilot/control analytics view.                                                                                                                                                                    |
| Training    | Dashboard, programs/routes, courses (including scheduled live lessons and their studio), events/QR and six-digit attendance codes, assignments, attendance, results, certificates and analytics. |
| Storefront  | Read-only product catalog through the backend; the configured Inkar website is preferred, with a local snapshot as outage fallback.                                                              |
| Settings    | Language/timezone/session settings.                                                                                                                                                              |

Campaign rule authoring validates the editable request contract before saving. Optional barcode, iPartID and goal
fields may remain empty. Once a goal or a comparison row is started, its conditionally required fields are marked
inline. Backend `fields` paths are mapped back to the exact pair/input; a hidden recommendation section opens
automatically, the first invalid control receives focus, and the form keeps an accessible error summary. Read-only
Medusa name/brand/MNN snapshots are bounded to the backend contract during request normalization so external catalog
metadata cannot leave the editor in an error state the user has no way to repair.

In each replacement or cross-sell pair, the campaign product and up to four additional offered
products have independent pharmacist reward amounts. New offers start at `0` (no reward); an
explicit positive KZT amount makes only that offer's POSM card green. Old rules without an
individual amount retain their previously inherited campaign reward until edited. The preview
uses the same green/white distinction and preserves the configured offer order. In the local
unification candidate both editor save buttons prepare one campaign-and-rules bundle. Saving
does not change the published offer. Explicitly inherited rewards follow the campaign reward
when that saved bundle is applied; explicit per-offer rewards stay independent. Historical
unknown inheritance is preserved rather than guessed.

An already issued recommendation in an open receipt keeps its event's quoted price and reward,
including after publication changes. A new receipt uses current published terms. Delayed shown/
outcome requests keep their original event identity even after the rule is retired.

The trigger of a pair can remain a specific Medusa product or be selected from an imported ACC
group, subgroup (shown together with its parent group), or INN. The taxonomy selector displays
the active snapshot and eligible member counts; only scopes with unambiguous barcode members are
available. A new broad-scope pair starts as a draft and requires an explicit activation decision.
The offered products and their independent bonuses are unchanged. Saving and reopening a campaign
must round-trip both old product pairs and broad-scope pairs without reclassifying or deleting them.
The Rules section labels these trigger kinds distinctly but remains read-only. The ACC file is a
versioned, manually imported snapshot, not a live prices/stock feed.

The new-campaign modal starts with “What triggers the recommendation?” and offers exact catalogue
product, ACC group, subgroup, or INN. Only the relevant searchable picker appears; ACC choices
show their parent, eligible barcode-member count, and snapshot source. The user then chooses
replacement versus cross-sell and the separate catalogue product to offer. The two exact products
cannot be the same. Campaign name and reward follow, while dates, cover, identifiers, and media
overrides are collapsed under additional settings. A single POST creates the campaign and its
first pair atomically as drafts; the form navigates to the campaign detail for card review.
In the local unification candidate the manager saves the enabled recommendations, then uses
the separate Apply action for that saved revision. Unsaved edits cannot be published. Applying
an existing paused campaign preserves its pause. Revision or published-source conflicts require
an explicit reload/discard, without silent retry or overwrite. MANAGED accounts cannot bypass
this process through the old direct content endpoints; LEGACY contracts remain available until
controlled account conversion. The old product-only POST remains compatible for legacy clients.

Administrator caches are bound to the authenticated account and role. Switching account or
logging out detaches and clears the old client; late requests cannot populate the next session.
Prepared campaign bundles and access-management data are never persisted. A 401/403 removes
the denied projection, while a same-account temporary server failure can retain ordinary cached
data. These boundaries do not clear the separate learner session or course progress.

Exact-product triggers retain all confirmed barcode aliases, not just a primary EAN. The picker
shows the barcode count/list and accepts a product with a usable `barcodes[]` even when the legacy
`barcode` is empty. Website `WARE_ID` is joined case-insensitively to the active reviewed ACC snapshot;
only barcode ownership unambiguous across that snapshot is usable. Marketplace SKU and similar names
are not scan identifiers. Unlike variant strengths/forms/packs are not merged. A product with no
confirmed barcode or explicit Standard-N iPartID remains unavailable as an exact scan trigger.
Fresh metadata for the same product IDs must refresh the picker, including previously disabled rows.

Create/edit/read/price refresh preserve the alias list and a compatible primary `barcode`. The backend
checks browser-provided plural aliases against the selected source or saved identity. A complete
new source identity replaces revoked aliases (including an explicitly empty mapping); an unavailable
or incomplete source does not erase the saved identity. This does not publish a draft or activate a
medical replacement. All four trigger kinds and per-offer reward settings remain independent.

The initial exact-product picker disables products without a usable barcode or iPartID and explains
why they cannot trigger a cash-desk scan. This restriction does not affect offered products or ACC
scopes. Catalogue prices are resolved by the backend from the configured source; the browser's
preview price is not the campaign price authority.

## State and Data

The pharmacist table shows IIN/JSN as a separate text column, retaining leading zeroes. Passwords
and password hashes are never returned or rendered; only the existing set/change actions are
available to authorized roles. Unknown/absent IIN values are shown as a dash, not invented data.
Connected cash desks additionally show a fixed-label staff sign-in diagnostic. Unknown client codes
are never rendered verbatim. `authenticated` is self-reported operational telemetry, not proof of
employee identity, eligibility for money or successful visual acceptance on a real Windows desktop.

The connected-cash-desk card is a live view, not a permanent installation registry. Its Excel button
exports the same active heartbeat snapshot with pharmacy name/city/address, Windows device id, last
heartbeat and the customer-screen state. `Есть` means Windows reports at least two monitors, `Нет`
means one monitor, and `Не определено` means the POSM client has not yet rolled forward to a version
that sends monitor topology. The last state becomes known automatically through POSM auto-update;
reinstalling pharmacy packages is not required.
The detailed live-device list starts collapsed and has an accessible expand/collapse control; the
summary, coverage warning and Excel export remain visible while the long list is collapsed.

- `src/app/store.ts` owns UI/session state: auth user/tokens, period, language, sidebar state,
  command palette, role switcher, contract modal.
- `src/lib/api.ts` owns the axios singleton and token refresh. It de-duplicates refresh requests.
- TanStack Query is used for server state. Mutations invalidate focused query keys.
- Query cache is persisted to localStorage and cleared on logout.
- DTO types live in `src/lib/api-types.ts` and should mirror backend DTOs exactly.

The dashboard journal shows metrics only after a successful response. An initial request failure
displays an explicit error and retry action, not an empty journal or zero sales. A failed automatic
refresh retains the last successful rows and metrics with a stale-data warning. Its rolling window
is configured on the backend and does not follow the header's month selector.

Manufacturer reports use a separate `/dashboard/reports` form rather than the recommendation
editor. HQ explicitly selects a campaign (including archived ones) and an inclusive 1–366-day
Almaty date range, then downloads the backend PDF. Browser and server both restrict export to
`HQ_HEAD`/`SYSTEM_ADMIN`; a brand-manager role is not proof of campaign ownership. The form
never writes campaigns or sales, retains selections on errors, rejects non-PDF downloads and
cancels an unfinished request on navigation. Confirmed replacement sales mean “approved”; the
0–120-second recommendation-sale subset is separately labelled. Methodology, source limitations
and the daily chart are inside the PDF; there is no second KPI request with a different cutoff.
See [rollout and reporting contract](21-rps-rollout-and-manufacturer-reports.md).

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

HQ routes are protected by `RequireAuth`; public learner/merch-claim entry points
perform their own pharmacist authentication and never grant access to `AppShell`.
The axios interceptor adds
`Authorization: Bearer <accessToken>` and performs refresh on 401. If refresh fails as auth failure,
the store is cleared and the user returns to login.

Manual receipt approval/rejection, Excel sales import and balance adjustments are limited to
`SYSTEM_ADMIN`, `HQ_HEAD` and `FINANCE_REVIEWER`. Other business roles retain receipt/detail
and pharmacist/balance reads, but do not see reconciliation mutation controls. The current
pharmacist registry displays balances read-only; ordinary profile/password PATCH requests omit
`balance` entirely. Changing another pharmacist's password or phone is restricted separately to
`SYSTEM_ADMIN` and `HQ_HEAD`, not `FINANCE_REVIEWER`; password reset controls follow that policy.
Backend authorization remains authoritative even if browser controls are bypassed.

Legacy navigation and direct routes keep their existing role ceilings; managed accounts also require current server permissions:

- `TRAINING_MANAGER` gets the dedicated training workspace plus `AI Exam` and lands on `/lms` after login;
- `REGIONAL_MANAGER` and `TRAINER` get the training workspace with actions narrowed by backend capabilities;
- in the dedicated training workspace, dashboard, programs, courses, offline events, assignments,
  attendance, results, certificates, analytics, and training settings are first-class sidebar items;
  these destinations are grouped under the expandable Training module; the duplicate horizontal tab bar remains hidden in the dedicated training workspace;
- `HQ_HEAD` keeps all operational sections and also sees `Training` and `AI Exam` in read-only mode;
- other core business roles keep operational and analytics sections without training navigation;
- `SYSTEM_ADMIN` retains its legacy navigation ceiling. It is not inferred to be the designated HQ owner and receives no new Merch access from that role alone.

This is not only a visual restriction. `SecurityConfig` separates `/api/admin/training/**`,
`/api/admin/lms/**`, and `/api/admin/ai-exam/**` from the rest of `/api/admin/**`. A training token
calling catalog, rules, screens, finance, settings, or other HQ APIs receives JSON `403 FORBIDDEN`.
An `HQ_HEAD` token can read training data and export reports, while every training mutation returns
JSON `403 FORBIDDEN`; the frontend mirrors this contract by hiding all editing controls.
The LMS roster uses `/api/admin/training/pharmacists`, a read-only projection for assignments.
Managers can open the certificate editor from the certificates section. Its settings belong to one
training program: ePharm and partner logos, signer, and validity period. The editor previews the
certificate composition before saving; only roles with `canManagePrograms` can change it. The
participant, completed program, issue date, and verification QR come from the actual certificate
when it is issued, not from the sample shown in the editor.

The training workspace also accepts an Excel course template. Import creates a draft for review,
not a published course; the manager can inspect lessons and tests before publishing. Program covers
are uploaded through the backend and stored as returned URLs rather than typed as arbitrary links.

## Pharmacist Onboarding

### Merchandising executor confirmation

CRM may open `https://epharm.inkar.kz/learn/merch/claim#challenge=...` for a rewarded task.
The signed capability is removed from the URL before telemetry starts and kept only in page memory;
refreshing the page requires scanning the task QR again. The page reuses the pharmacist IIN/password
login and its existing session-token namespace, never the admin session or the editable CRM executor name.

The backend validates the CRM challenge, active pharmacist and exact pharmacy assignment before
returning task context. The page then requests a fresh, purpose-scoped SMS only after an explicit
button press. The recipient is the account's server-bound phone, displayed masked and not editable.
OTP expiry, resend cooldown, expired login, wrong pharmacy and retryable network errors have explicit
states. This flow neither activates Standard-N accounts nor changes ordinary pharmacist login.

After confirmation the page offers only the backend-issued, allowlisted
`https://crm.inkar.kz/staff#claim=...&grant=...` handoff. ePharm JWTs, passwords and SMS codes never
leave the ePharm origin; the short-lived opaque grant is not saved in browser storage. Returning to
CRM identifies the executor but does not itself credit money. Rewards require actual completion and
the manager's approval. Existing anonymous task execution remains a separate, non-rewarded path.

### Existing onboarding

Mobile self-registration creates a `pending` pharmacist without a pharmacy assignment. In
`/pharmacists`, HQ selects an active pharmacy and activates that profile. This assignment is used
when backend maps the active Standard-N cashier to an internal pharmacist for analytics and bonuses.

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
