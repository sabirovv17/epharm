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

The admin console is a desktop HQ tool with a 1280px minimum width. The separate `/learn`,
`/producer` and public task portals do not inherit that minimum and support narrow screens.

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
Manufacturer plans now have the native `/merch/placements` workspace, guarded independently by
`canViewMerchPlacements`. The native `/merch/tasks` queue is independently guarded by
`canViewMerchTasks`; inventory or placement rights do not grant task rights.

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

The separate **Личности и перенос** tab keeps identity migration out of the default grant editor.
HQ explicitly records a verified person with an internal reference and current-password proof;
then it may bind a source CRM account to an existing managed account of that exact person, or
provision a new managed account with empty grants. No FIO/email match creates a binding. Source
inactive status and password compatibility are validated by the server; hashes never reach the
UI. Advertisers continue in their producer SMS realm. A narrow paginated
`/api/admin/access/pharmacist-options` picker supports pharmacist/person links without reading
phone, IIN, bank or balance fields. An existing link is not reassigned. Passwords/references are
not persisted; cancelling or completing the form clears password state. Source identity actions
do not authenticate pharmacists on POSM or activate RPS.

### Manufacturer plans and separate producer portal (local candidate)

The internal workspace lists authorized plans, opens fresh details and preserves cancelled item
history. The picker uses `/api/admin/merch/placements/producers` and `/placements/catalog`, scoped
to the intersection of a manufacturer and CRM branch. It does not require or borrow inventory
permissions. Draft creation/save do not reserve places. Submit, approve, reject, cancel and direct
administrative assignment require explicit confirmation, the fetched revision and current server
`can*` flags. Reject/cancel require a reason. Conflicts are never retried or rebased automatically.
Server quotes are authoritative, withheld amounts render `—`, and unresolved historical producer
links remain read-only. The saved plan and each item retain their status and origin.
For an unresolved plan with no owner, only the separate global and object-level server binding
capabilities reveal HQ's explicit producer-binding form. It requires a complete active company,
current revision, private audit reason, acknowledgement and current password. The reason is not
sent to public notes or the producer profile. There is no reassignment or automatic name match.

`/producer/login` and `/producer` are a separate public producer realm, outside the admin shell and
`/learn`. SMS request/verification and rotating refresh tokens use `/api/producer/**` exclusively.
The dedicated fetch client sends producer bearer tokens with `credentials: omit`; it never uses
admin/mobile tokens or interceptors. Its session-storage namespace is `epharm.producer.session.v1`.
Refresh is single-flight; logout changes the local epoch immediately and late old-session responses
cannot restore a logged-out or different producer. OTP values are never stored. Disabled auth
(including default-off feature responses) is shown as unavailable. When enabled, a verified SMS
may create a new producer identity; the first login then completes the typed company profile.
The frontend never invents a registered company or derives one from a matching name.

The external picker reads active branches and fetches the quote for the selected branch only.
Create omits `producerId`; the server derives identity from its producer principal. External
screens have no manufacturer selector, approval/rejection or administrative assignment actions.
Reservations show the server's three-business-day period, state, deadline and Asia/Almaty timezone.
No read expires a hold, and no booking status proves a bank payment. Payment remains disconnected.

The producer may read/edit its own typed company profile using GET/PUT `/api/producer/profile`,
with revision CAS. Null legacy names show “Компания не указана”; names are never guessed from a
brand. An incomplete profile can retain drafts but must be completed before submit/assignment.
The form preserves optional legal/bank metadata, supports explicit empty values and never writes
phone/password/tariff fields. Bank metadata does not enable payments. Profile conflicts require
an explicit reload; a stale profile cannot silently overwrite newer values.

Placement permissions require both branch scope and a separate manufacturer scope on each action.
`allProducers/producerIds` cannot be inferred from `allMerchBranches/merchBranchIds`, combined
between actions, or populated by an empty selection. Server delegation limits remain authoritative.
These flows are source/tested locally; they are not evidence of a production release or real SMS delivery.

### Native pharmacist tasks and financial exports (local candidate)

The HQ task queue uses `/api/admin/merch/tasks` and its task-scoped branch selector, not the
inventory API. New tasks require an active authorized branch, a title, a future due date and
at least one required item. Deadlines are entered in Asia/Almaty time. Per-item instructions,
photo minimums and checklist rows are preserved; a reward can only be configured when the
selected branch explicitly returns `canConfigureReward`. Queue pagination does not invent a
global count or a server-side status/search filter.

Every item action uses fresh server `canStart/canUpload/canSubmit/canReview` flags. Photo uploads
are authenticated private JPG/PNG bytes (20 MB / 12 MP server gate); failed uploads are not
retried automatically. A retained `clientUploadId` protects an explicit retry of the same file.
Submission includes the current attempt number and completed checklist IDs. Review requires
the selected attempt ID, current item version, an explicit reason and confirmation. A 409
requires reloading, not a silent retry. Prior photos and attempts remain history and cannot
satisfy a new attempt's photo minimum. The UI never overrides server self-review restrictions.

Imported in-progress/submitted items with no current attempt cannot be claimed by opening a
new QR link. Only explicit HQ leaf capabilities reveal source continuation actions: request a
new attempt or adopt the submitted historical report for review, with current version and a
reason. Preserved-source review requires its own manual acknowledgement; this is distinct from
the unverified free-link executor acknowledgement. Neither acknowledgement confirms identity
or credits a paid historical report automatically.

`/tasks/staff?task=1#task=...` is the new native public task route. Its capability is consumed from
the fragment into memory and removed from history before telemetry starts. It is never accepted
from query parameters or written to storage. Public task requests send `X-Merch-Task-Token`
without cookies/admin/learner authorization and use a no-referrer policy. Free tasks support
their own allowed photo workflow. Paid tasks expose only metadata before linking to the separate
`/learn/merch/tasks/:id` pharmacist session; there is no RPS or Standard-N lock.

For an unbound paid task, only `OTP_NOT_VERIFIED` opens the native executor confirmation flow.
The original QR capability plus the current learner session fetch immutable task context.
SMS is requested only by the user; the recipient phone is read-only and server-derived. Verify
sends only challenge ID and code. An existing binding skips SMS; a wrong pharmacy/person remains
denied. SMS/delivery feature flags remain default-off and tests use mocked providers. Legacy
`/merch/staff` CRM compatibility routes are unchanged.

`/finance/ledger` reads wallet postings, not a new sum of underlying rewards. Period/source
filters apply identically to the report and its separate Excel button. Missing historical
pharmacy or balance is shown as unknown, never replaced by the current pharmacy or zero.
Scoped unresolved counts may be withheld. Registry, payout and single-campaign XLSX downloads
have separate server export capabilities; the payout period means batch creation, and an
approved batch is not proof of a bank transfer. Registry search/training filters not supported
by its export API are explicitly excluded. XLSX MIME is checked so an error page is not saved
as a spreadsheet. These local flows do not constitute a production deployment.

Separate native merchandising XLSX buttons export inventory for the displayed branch/calendar,
placements for the applied producer/status, and the task queue's authorized history. Opening a
detail does not filter the queue export; no hidden period is added. Courses and programs have
separate XLSX buttons covering their authorized registries, without answer keys; table text
search is not silently treated as an export filter. Every button requires its own fresh server
export capability. A 413 asks to narrow filters and 429 asks to retry later; no truncated file or
HTML error is accepted as Excel. These buttons do not alter lesson content, test scoring or the
separate learner session.

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
- `/learn/merch/tasks/:taskId` — native task detail and paid executor confirmation in that same
  personal pharmacist session.
- `/tasks/staff` — native fragment-capability free task / paid task metadata portal.
- `/producer/login`, `/producer` — separate producer SMS realm.

Protected under `AppShell`:

- `/dashboard`
- `/dashboard/reports` — HQ/SYSTEM_ADMIN XLSX reports and explicit producer provenance, linked from Dashboard
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
- `/finance/ledger`
- `/merch/inventory`, `/merch/placements`, `/merch/tasks`
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

Manufacturer reports use a separate `/dashboard/reports` form. The current HQ interface exports
XLSX only; legacy campaign-PDF endpoints remain compatibility APIs, and certificate PDFs are
unchanged. HQ explicitly selects one campaign or a producer and an inclusive 1–366-day Almaty
period. The producer index lists confirmed binding counts and unbound campaigns; brand/author/name
matching is never a binding. Export requires the separate fresh server report capability and
financial/view intersection. Errors do not turn into files; unfinished requests are cancelled.
Confirmed replacement sales mean “approved”; the 0–120-second recommendation-sale subset remains
separately labelled. Campaign reports retain methodology and source limitations.

With `canManageCampaignProducer`, HQ may explicitly add or retract a campaign's producer binding
using its current revision, an evidence reference and either a bounded approved interval or an
explicit whole-history confirmation. Overlapping/stale changes require a reload, not auto-rebase.
Retractions preserve original records. This flow does not change recommendation rules or bonuses.
HQ may add a business manufacturer for campaigns using an idempotent request ID kept only in the
open form's memory. An uncertain retry retains the same ID and payload. This does not create a
phone, SMS portal login or staff identity, and never auto-links a campaign.

Assignment/result XLSX buttons have independent capabilities. Assignment export carries applied
format/status/program filters across all pages; result export covers recorded result history,
without a hidden date interval or answer keys. Any active unsupported name search disables the
button with a clear explanation. If the legacy training dashboard/list is denied, an explicitly
separate export-only panel remains available under the new export permission; it does not reopen
legacy read, CSV or result-edit permissions. Test scoring and learner video content are unchanged.
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
