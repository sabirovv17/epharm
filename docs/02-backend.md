# Backend

## Pharmacist access recovery candidate — 2026-11-10

All pharmacist password writers share the BCrypt limit of 72 UTF-8 bytes without
trimming or truncation. Existing route minimums remain: POSM 4, HQ 6, learner/reset 8.
Initial learner activation validates before phone locks/proof consumption, so an
invalid new password can be corrected without spending the verified activation.
Existing learner password verification is unchanged; older incompatible passwords
can be replaced only through the established verified recovery flow.

The account login limiter hashes the same trimmed IIN used by validation/lookup.
Whitespace variants share the original attempt budget/window; no counter reset,
new quota or weaker password proof is introduced. Unknown accounts, wrong passwords
and blocked POSM accounts retain the generic invalid-credentials response.
Password reset request, verification and completion use the same trimmed IIN;
surrounding whitespace neither changes account lookup nor creates a new quota key.

Only after a correct POSM password, an active unassigned account receives 403
`STAFF_PHARMACY_ASSIGNMENT_REQUIRED`; a different assigned pharmacy receives 403
`STAFF_PHARMACY_MISMATCH`. Pending accounts retain 200/pending without a grant.
These results explain an HQ action, never activate or transfer a person, reveal
the other pharmacy, or grant wallet access. Heartbeat `session_revalidating` and
`pending_approval` are bounded diagnostics, not authority.

Scheduled Standard-N directory replacement uses an explicit transaction for
delete+insert+flush, including the scheduled self-call. External Firebird reads
occur before that transaction. A failed replacement rolls back; independent
readers keep the previous committed snapshot until the new snapshot commits.
Repeated staff logout does not overwrite the first recorded revocation time.
No migration or production data mutation is part of this candidate.

## OTP admission and retry contract (local post-157 candidate)

OTP/phone-lock entry points share a fair admission advisor **before** the
transaction advisor. `SMS_MAX_CONCURRENT_TRANSACTIONS` defaults to 4, capped at
`floor((Hikari maximum-pool-size - 2) / 2)`: mobile wrappers may retain an outer
transaction while running a `REQUIRES_NEW` OTP transaction. The pool must contain
at least four connections. A caller may wait at most two seconds for a permit before
opening a transaction, so a short healthy burst does not immediately fail. An
interrupted wait preserves the caller's interrupt flag. Nested annotated calls reuse admission; rejection is
503 `OTP_PROVIDER_UNAVAILABLE`, without borrowing a connection or inventing a retry
deadline. The separate shared provider-call budget `SMS_MAX_CONCURRENT` defaults
to 2. Phone locks, durable attempt counts, invalidation and one-use proofs remain
inside their original transactions. Cancellation/error releases in-memory permits;
it never grants authentication or automatically retries a mutation.

The released SMS-free initial registration remains SMS-free and permanently
advertises `registrationSmsRequired=false`. Its phone-lock/BCrypt transaction also
uses admission; overload can return 503, but cannot restore SMS registration or
label a self-declared phone as verified. Reset/activation, genuine previously
issued proofs and other OTP channels retain their security contracts.

This bounds slow-SMS/lock-storm damage; it does not increase BCrypt CPU capacity
or replace a production-sized load test. Admission is not a separate connection
pool: unrelated authentication requests can still wait for a shared account-row
lock. Account locks are intentionally preserved; cross-path contention needs an
integration stress regression before claiming all database contention is gone.

Known account/device/SMS 429 windows return matching positive integer `Retry-After`
and optional JSON `retryAfterSeconds`, rounded up from their actual remaining
deadline. Unknown provider availability has no invented cooldown. An exhausted
POSM OTP proof returns `retryAction=REQUEST_NEW_OTP` with the same 429/code and no
countdown: its attempt budget cannot recover by waiting. Clients may preserve
entered form data but must never replay passwords/OTP, send SMS, or retry account
mutations automatically.

Release publication takes a PostgreSQL transaction advisory lock per normalized
platform and flushes retired current rows before insertion. New V099 enforces one
current row per platform with a partial unique index. Historical duplicates fail
migration rather than selecting a signed release silently; resolve that condition
explicitly before deployment. No previously applied migration is edited.
An application rollback to the previous binary may read schema99, but publication
of another POSM release must remain frozen until the new binary is restored: the
previous publisher does not flush retired rows before inserting under the new index.

The pool8/pool10 regression uses real Spring transactions/advisors and Hikari with
fake physical JDBC connections, not a PostgreSQL/SMS transport. It holds the budget,
checks bounded rejection without borrowing connections, and verifies that unrelated
work can still use the two remaining slots. Separate regressions cover a healthy
burst longer than one second, caller interruption, nested calls and permit cleanup.
The fake JDBC configuration is explicitly `TestConfiguration`; Boot's real component-scan
exclusion filter must keep it out of application and integration-test datasources.

Release acceptance additionally requires full Spring/PostgreSQL16 CI on the exact
revision and the unchanged isolated500-desk registration, login and session workloads.
These use synthetic identities, positive recommendations/playlists and SQL financial
checks; they never call real SMS or production. Current execution evidence is recorded
in the release section of the root `PLAN.md` and the PR. A local CPU-limited load run
does not certify production capacity. Production deployment and real SMS acceptance
remain separate explicit gates.

## RPS registration availability

The authenticated POSM identity is `(pharmacyId, deviceId)`, not the Windows/device
name alone. Staff registration, login and SMS device budgets and staff advisory locks
use that complete identity. Independently authenticated pharmacies named `KASSA` or
`MANAGER` must not consume one another's quota or serialize one another's employee
requests. Multiple processes in the same pharmacy/device still share a budget.
The global IIN/phone limits, attempt durability, individual key checks and original
maximum/window values remain enforced. Existing hashed rate counters are not deleted;
the corrected device scope starts its own budget and ordinary retention removes old
counters. This change needs no migration and does not move existing pharmacists.

HTTP 429 means an attempt budget was exhausted, not that the service crashed. The
POSM form keeps the entered data and offers continued cash-desk work; it must not
automatically retry an account mutation. Transport failures and 5xx are separate
diagnostics. The isolated RPS workload in `load-tests/` checks the HTTP result,
process-bound grant, duplicate rejection and actual pharmacy assignment in SQL.

## SMS-free initial RPS registration (v0.2.13)

Initial RPS registration always advertises `registrationSmsRequired=false` in the
existing individual-device capability response. The historical
`POSM_STAFF_REGISTRATION_SMS_REQUIRED` setting cannot re-enable SMS in this backend;
its configuration mapping remains only for older rollback binaries. POSM sends IIN, full name, phone, password and
`deviceContext` to `POST /api/posm/staff/device/register-without-sms`. It sends no
code/challenge and makes no SMS-provider request. The active profile belongs to the
authenticated device's pharmacy and receives the usual process-bound employee grant.

V098 adds only nullable `pharmacists.phone_verification_status`: old rows stay
unclassified (`NULL`); this route records `self_declared`, not verified ownership.
Successful normal RPS SMS registration records `sms_verified`. Existing accounts,
IIN/phone uniqueness, password/vault checks, durable registration limits and individual
device authentication remain enforced. Declaring a number grants no password-reset,
mobile-login, merchandising-payment or Standard-N identity proof.

New RPS register-OTP issuance always returns 503 before taking phone locks or
contacting the provider, without sending SMS; already
issued genuine registration codes may still complete. Login, activation, reset,
`/learn`, mobile and producer OTP contracts remain unchanged. POSM 1.0.81 never
restores the registration SMS form for an unavailable, missing or old capability:
unsupported registration keeps the entered form, explains the problem and permits
cash work. No request is automatically replayed. Older installed binaries need the
signed client update to remove their historical SMS controls.

## POSM employee login independent of Standard-N (v0.2.9 candidate)

`GET /api/posm/staff/capabilities` retains protocol 2 and advertises
`deviceEmployeeAuthVersion: 1`. POSM 1.0.77 uses `/device/login`,
`/device/register`, `/device/session/check` and `/device/logout`: the device
context contains the actual Standard-N process instance and an optional independently
observed Standard-N employee context. Missing or ambiguous database evidence does
not prevent ePharm password login. It does not prove a Standard-N identity match.

Both registration routes assign the active pharmacy authenticated by the individual
POSM device key, including legacy `/register` without a database session. This is
the same authoritative pharmacy boundary used for POSM sales. A contextless legacy
registration returns `registered` without inventing a strict session; device
registration returns its own authenticated grant. Existing pending/blocked or
already assigned accounts are not automatically activated or transferred. Historical
unassigned records require a verified, individually audited HQ activation.

V097 adds `posm_device_staff_sessions` separately from strict legacy sessions, plus
expiry/revocation indexes and pharmacist/device change revocation triggers. Password,
phone proof, active pharmacy/device, cross-pharmacy access and shared rate limits
remain enforced. A pharmacy package can be installed on multiple terminals: login
replaces strict/device grants only for the same device/pharmacy/process. Password,
account and device-key revocation still cover all corresponding processes. The
process identifier is a client observation, not hardware or financial proof. Late first reliable Standard-N evidence binds the existing grant;
a different known cashier/session/workstation or process revokes it. Unknown evidence
does not erase known evidence. Without readable source data, a user switch inside
the same process cannot be reliably detected. No recommendation-sale attribution or
bonus crediting is enabled by this login.

When the server/SMS is unavailable, the client releases mandatory presentation and
permits work. POSM receipt, recommendation, display and `/learn` contracts remain
independent. Roll back the application without deleting the additive session table;
1.0.77 disables its new flow when an older server lacks the capability.

## Local candidate: internal self-password change

`POST /api/admin/auth/password` accepts only `currentPassword` and `newPassword`
for the authenticated internal account. It is a self-service route for all admin
roles, not an account-management grant and not a pharmacist/producer/device route.
Success is 204; wrong proof or invalid new password is 400, not an authentication
refresh challenge. New passwords require 12–72 characters and at most 72 UTF-8
bytes. No supplied password/hash/token is written to the change audit.

The local V093 migration adds admin `session_version` and password-change metadata.
Admin access JWTs carry `sv`; every authentication compares it to the current
account. A legacy token without `sv` is version zero and works only while the
account remains at zero. Password update locks and reloads the account, verifies
the principal version/current password, increments the version and revokes refresh
tokens transactionally. Login and refresh use account-before-token lock ordering.
Refresh validates/rotates the credential under that lock before inspecting account
status: revoked/expired credentials return 401 even for a blocked account; an
otherwise valid credential for an inactive account remains 403. A rejected refresh
does not issue new credentials, and its transactional rotation rolls back.
Pharmacist token handling is unchanged. Ordinary logout retains its existing
refresh-only revocation semantics; it is not upgraded to global JWT revocation.

The new password endpoint has a persistent per-account attempt budget (five
attempts per 15 minutes). Its separate transaction commits before password proof
checking, so failed proof cannot roll back the budget. Exhaustion is 429; there
is no permanent account lock. This is not a general limiter for existing login,
HQ reauthentication, SMS or other proof endpoints.

Release prerequisite: all serving backend nodes must enforce session versions
before immediate old-token revocation is claimed. An old binary during a rolling
rollout or rollback ignores this fence. V093 is only a local unapplied candidate;
DB transactions, concurrency and the real security pipeline require PostgreSQL
integration verification before release.

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

| Package                               | Responsibility                                                                                      |
| ------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `auth`                                | Admin auth, JWT, refresh tokens, roles.                                                             |
| `mobile.auth`                         | Pharmacist OTP auth, registration, refresh/logout.                                                  |
| `mobile.profile`                      | `/api/mobile/me` profile and authenticated self balance/recommendation APIs.                        |
| `mobile.catalog`                      | Public mobile catalog with a configured website source and local snapshot fallback.                 |
| `mobile.promotions`                   | Public active promo campaign feed.                                                                  |
| `mobile.receipts`                     | Authenticated receipt upload/history.                                                               |
| `mobile.pharmacies`                   | Public active pharmacy list.                                                                        |
| `catalog`                             | Internal/admin product master data used by rules.                                                   |
| `promo`                               | Campaigns, Medusa product snapshots, tiers, campaign-generated rules.                               |
| `rules`                               | Rules Engine admin CRUD and POSM matching logic.                                                    |
| `receipts`                            | Receipt storage, moderation, POS/Excel reconciliation, bonus crediting.                             |
| `pharmacies`                          | Chains and pharmacies.                                                                              |
| `pharmacists`                         | Pharmacist registry, status, balance.                                                               |
| `finance`                             | Payout batches, items, approval, scheduler.                                                         |
| `finance.merch`                       | Disabled-by-default verified CRM executor grants, signed approvals and idempotent monetary rewards. |
| `screens`                             | Broadcast/screen media and playlists.                                                               |
| `banners`                             | Admin-managed mobile banners.                                                                       |
| `medusa`                              | Historical Medusa client and durable read-only product snapshot.                                    |
| `storefront`                          | Read-only client and DTO mapping for the current Inkar website catalog.                             |
| `posm`                                | POSM recommendations, outcomes, sales, playlists, heartbeat, CDP.                                   |
| `fulfillment`                         | Signed storefront order ingest, pharmacist cash-pickup queue and actions.                           |
| `appupdate`                           | POSM app release metadata and auto-update endpoint.                                                 |
| `merchtasks`                          | Optional authenticated CRM task bridge, exact staff URL validation and bounded concurrency.         |
| `training`                            | Programs, routes, assignments, events, attendance, results and awards.                              |
| `lms`, `ai_exam`, `lift`, `dashboard` | Admin sections and reporting.                                                                       |
| `reporting`                           | HQ-only campaign JSON/PDF, confirmed displays, attributed/0–120-second sales.                       |
| `posm.staff`                          | Optional device-scoped employee authentication and purpose-bound SMS recovery.                      |
| `shared`                              | Security, errors, media proxy, storage, validation, dev reset.                                      |

## API Map

Shared IIN validation checks twelve ASCII digits and the two-pass checksum, and
preserves rejection of the empty `000000000000` identifier. It does not decode a
birth date, century or sex: see the [official explanation](https://www.gov.kz/org/migration/press/news/details/132237?lang=ru).
This applies to staff registration/login, pharmacist administration and the learner
web authentication API. Format validation does not prove government issuance or
ownership; authentication, uniqueness and identity observations remain separate.

POSM staff identity is an independent optional API under `/api/posm/staff`: capabilities,
login, OTP request, registration, password reset/activation, session validation and logout.
Every route requires a non-legacy individual `X-Posm-Key`; opaque staff grants also bind
the pharmacy/device and observed process/cashier context. Pending registration does not
assign or activate a pharmacist. `POSM_STAFF_AUTH_ENABLED=false` is the safe default.
Neither this flag nor a failed login gates Standard-N, recommendation delivery or sales.
See [manual staff acceptance](rollout/rps-auth-test.md).

Protocol 2 additionally advertises `nameFallbackSupported=true`; the new client requires this
marker and disables its whole staff flow on an older server. `StaffContext` appends optional
`standardNFullName` and `standardNFullNameStatus`, persisted by V096 and bound to POST session
validation. Legacy GET projects both IIN and name observations away for old four-field clients.
An available IIN always takes priority. Only missing/unavailable IIN permits exact full-name
comparison against the already authenticated profile: case/whitespace and word order are
normalized, with all words retained. Initials, generic role labels and fuzzy/partial matches
do not identify a person. There is no account lookup or financial seller binding by name.
IIN-only diagnostics remain `UNKNOWN` for this path; separate permission-filtered identity
method/status/check time explain name matches and mismatches without exposing source names.

OTP issuance/verification serializes Daribar's phone-wide codes across mobile and POSM.
New staff challenges are purpose/account/device-bound and one-use; independent transactions
preserve invalidation and attempt budgets on request errors. Changing staff status/pharmacy/
password or device credentials revokes grants. Password reset also revokes mobile refresh
tokens; previously issued mobile access JWTs retain their existing short lifetime until expiry.
Transient expired staff challenges/sessions/rate counters are purged after 24 hours.

RPS passwords accept 4–128 characters and at most 72 UTF-8 bytes, excluding all-whitespace
values. Existing-password mobile/`/learn` login accepts the same minimum; unrelated password
creation policies are unchanged. Both HTTP password-login controllers use one durable hashed-IIN
budget (10 attempts / 5 minutes) before the authentication transaction. Failed logins do not
roll back the budget, and switching routes or devices cannot bypass it. Device/SMS limits remain.

The existing POSM heartbeat accepts optional `staffAuthStatus` from a strict fixed allowlist.
Unknown/free-text values are discarded without rejecting the heartbeat; an omitted value clears
obsolete diagnostics from an older client. The connected-screen API exposes the bounded current
status for operations only. It is never consulted for employee authorization, account activation,
sale attribution or wallet eligibility. Redis/local presence expiry applies normally; no new PII is stored.

### Admin

- `POST /api/admin/auth/login|refresh|logout`, `GET /api/admin/auth/me`
- `/api/admin/dashboard/summary`
- `/api/admin/dashboard/recommendations` — конверсия показ→продажа + время до продажи (V032)
- `/api/admin/reports/campaigns/{promoId}` and `/{promoId}/pdf` — HQ/SYSTEM_ADMIN-only campaign reporting;
  inclusive `from`/`to` dates in Asia/Almaty, at most 366 days, `no-store`. Confirmed display ACKs,
  accepted actions, verified attributed sales and the separate 0–120-second subset remain distinct.
  See [report methodology and rollout](21-rps-rollout-and-manufacturer-reports.md).
- `/api/admin/catalog/products`, `/brands`, `/mnn-groups`, `/trigger-options`
- `/api/admin/rules/**`
- `/api/admin/promo/**`, including `/refresh-prices` and `/{id}/rules`
- `/api/admin/banners/**`
- `/api/admin/storefront/products`, `/products/{id}`
- `/api/admin/pharmacies/**`
- `/api/admin/pharmacists/**`
- `/api/admin/reconcile/**`, including `/submit` and `/import-excel`
- `/api/admin/payouts/**`
- `/api/admin/screens/**`, including `/connected`, `/broadcast`, and per-slot broadcast replacement
  at `/broadcast/slots/{1..12}`
- `/api/admin/app-releases/**`
- `/api/admin/lms/courses/**`
- `/api/admin/training/**`
- `/api/admin/ai-exam/questions/**`
- `/api/admin/lift`

Live lessons extend the existing course API rather than replacing older lesson kinds. The
administrator's live-room endpoints manage the broadcast state and interactions; the authenticated
learner endpoints expose only the assigned lesson's room. A learner cannot complete a live lesson
until the room has ended. Stream URLs are accepted only over HTTPS with provider-aware host checks;
the new `V059` migration expands the lesson-kind constraint after the already deployed `V058`
course-live schema migration without editing either previously applied migration.

`GET /api/admin/catalog/trigger-options?kind=acc_group|acc_subgroup|acc_mnn&q=...`
returns selectable scopes and metadata for the active ACC classification snapshot. Campaign rule
pairs keep the historical product trigger by default; optional `triggerKind`, `triggerValue` and
`triggerLabel` represent a broad ACC scope. The backend validates the scope against the active
snapshot when saving or activating it. POSM matching uses only an unambiguous exact cart barcode
for broad scopes, never a fuzzy product name; the response still names the actual scanned item.
The recommendation event and sale attribution paths deduplicate a sold offer within one receipt,
even if multiple grouped items triggered separate displays.

Trigger refs also accept optional `triggerConditions: [{kind, value, label}]`: up to three
distinct ACC group/subgroup/INN factors, excluding a repeat of the primary factor. All factors
must match the **same** unambiguous scanned barcode; separate basket lines cannot satisfy parts
of the conjunction. Active snapshot keys are validated on save/publication. Factor order and
labels do not change rule identity or bypass publication/financial permissions. POSM wire DTOs
remain unchanged. Duplicate conjunctions and simultaneous active replacement/cross-sell for the
same conjunction are rejected rather than silently losing a pair or suppressing both at runtime.
Publication also checks known ACC intersections, including confirmed aliases for exact products;
empty conjunctions remain editable drafts and known replacement/cross-sell overlaps cannot launch.
Cached pre-conditions admin editors must refresh before changing such a campaign. The current
editor sends `X-Epharm-Trigger-Conditions: 1` on rules/save/discard/apply; without it a compound
published/prepared/incoming set returns 409 before mutation. Simple legacy campaigns and POSM
requests are unaffected. The header is a compatibility signal, never an authorization grant.

`POST /api/admin/promo` still accepts the legacy campaign payload. For trigger-first creation it
also accepts optional `initialRecommendation: {type: "substitution"|"crosssell", trigger}`,
where `trigger` has the same shape as one entry in `PUT /api/admin/promo/{id}/rules`.
`medusaProductId` remains the product **offered** by the campaign; the initial trigger is the
scanned product or ACC group/subgroup/MNN that starts the recommendation. This path creates the
campaign and its first rule in one transaction and only as drafts. It rejects a missing offered
product, an immediately active campaign, an exact-product self-trigger, or a scope absent from
the active ACC snapshot. A failed rule cannot leave an empty campaign behind; existing clients
that omit `initialRecommendation` retain their previous behavior.

The impressions-and-sales journal uses its configured rolling analytics window, independently of
the header's selected month. PostgreSQL first selects the receipt candidates needed for the page,
including every receipt sharing the boundary timestamp, then expands their items. The final order
remains event time descending and expanded event ID descending. Complete counts, empty receipts,
per-item quantities/amounts and fastest sale attribution are unchanged; deep offsets still cost
more than initial pages. No events or accepted outcomes are synthesized by the dashboard.

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
- `GET /api/mobile/me/balance` — own available wallet balance, integer KZT.
- `GET /api/mobile/me/recommendations?page=0&size=20` — active authored recommendation groups.
- `GET /api/mobile/receipts`
- `POST /api/mobile/receipts` multipart.
- `/api/mobile/training/**` for the authenticated pharmacist's assignments, events and notifications.

#### Personal balance and recommendation API for the learning service

Both new GET routes require `Authorization: Bearer <pharmacist-access-token>` issued by
ePharm. This is the same principal used by `/api/mobile/training/**`, not the training
manager's admin account. An admin token (including `TRAINING_MANAGER`) cannot read these
routes. POSM device keys and JWTs issued by another service are not pharmacist credentials.
Never share `app.jwt.secret` with a consuming service.

The current account is reloaded for every call. Only `status=active` may use these routes:
a pending or blocked account returns `403 USER_NOT_ACTIVE`; a deleted account returns
`401 USER_NOT_FOUND`. This check applies even to access tokens issued before a status change.
An active account may read its own balance without a pharmacy assignment. The owner is
always the signed token subject, never a client-supplied phone, `pharmacistId` or header.
These identity selectors are not supported and cannot switch the current user.
Both responses set `Cache-Control: no-store` and contain no IIN, phone or other users' balances.

`GET /api/mobile/me/balance` returns exactly the existing common wallet projection
`pharmacists.balance`, without creating ledger entries or reserving funds:

```json
{ "balanceKzt": 42000, "currency": "KZT" }
```

`GET /api/mobile/me/recommendations` returns:

```json
{
  "items": [
    {
      "campaign": { "id": "promo_example", "title": "Example", "dateStart": null, "dateEnd": null },
      "type": "substitution",
      "trigger": { "kind": "acc_group", "value": "stable-acc-key", "label": "Group label" },
      "triggerProducts": [],
      "offers": [
        {
          "ruleId": "rule_example",
          "product": {
            "id": "product_example",
            "name": "Example product",
            "brand": "Example",
            "mnn": "Example INN",
            "volume": "30 tablets",
            "barcodes": [],
            "ipartId": null,
            "catalogPriceKzt": 2500,
            "catalogUpdatedAt": "2026-01-01T00:00:00Z"
          },
          "bonusKzt": 0,
          "script": "Configured text",
          "advantages": []
        }
      ]
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "currency": "KZT"
}
```

`type` is `substitution` or `crosssell`. Every rule must be active; a linked campaign must
also be active and within inclusive dates in `Asia/Almaty`. Missing, paused, draft, archived,
expired and future campaigns are excluded. Legacy manual rules without a campaign remain
supported with `campaign=null`, matching the POSM activity policy. A missing offered product
is omitted; an empty group is not returned. Reading does not record impressions, sales,
acceptances, progress or bonuses, and does not activate rules.

Groups preserve separate trigger and offered products, all four authoring modes
(`product`, `acc_group`, `acc_subgroup`, `acc_mnn`), plus legacy `product_any`/`mnn`.
`trigger.value` is a product ID, a list of IDs for `product_any`, a stable scope key for
`acc_*`, or a literal INN string for legacy `mnn`;
`exclude` and the presentation-only `label` are preserved where present. `triggerProducts`
contains known exact-product snapshots only; ACC groups are not expanded into an entire catalog.
Each campaign/type/trigger group contains up to five distinct offers in admin `offerRank`
order, with legacy bonus-descending and rule-ID tie-breakers. Bonus zero means no reward.

Pagination is by whole groups, never by individual offers: zero-based `page`, `size` 1–100
(default 20), `total` number of groups. Invalid bounds/types return `400 VALIDATION_FAILED`;
pages past the end return `items=[]`. Groups sort by most recently updated rule, then rule ID.
This is live offset pagination, not a frozen export: edits between calls can move groups.

This endpoint is a network-wide **directory of configured active recommendations**, not
POSM cart matching or a promise of display/medical equivalence. It does not check a current
receipt, local pharmacy stock, ACC membership of a scanned item, conflicts in that cart,
A/B eligibility, dismissals or personal goals. `catalogPriceKzt` is the last stored catalog
reference price with its update timestamp, not a guaranteed pharmacy price or stock statement.
All data is read locally; neither a retired Medusa origin nor the website is called per GET.

For a separate learning application with its own login, its unrelated user token or an
arbitrary ID is **not** accepted. Reuse the learner's existing ePharm pharmacist login/token
or implement an explicitly verified account-linking/federation flow with the owning team.
No external service, new issuer trust, CORS origin or account-linking flow is configured
by these endpoints. A training administrator's shared token must never stand in for a learner.
Existing mobile logout revokes refresh tokens; an already issued access token retains its
short expiry. These endpoints additionally reject accounts made inactive in the database.

The built-in learning portal at `/learn` already authenticates through `/api/mobile/auth/login`
and uses a pharmacist access token for `/api/mobile/training/**`. It can reuse that same token
for these routes on the same origin. The balance payload uses `balanceKzt`, not the `balance`
field in `/api/mobile/auth/me`; the recommendation directory is under `items`, not a bare array.
A pending account may sign in to the existing learner portal but still receives
`403 USER_NOT_ACTIVE` from the self-service APIs. Diagnose that account state explicitly;
do not replace the pharmacist token with an administrator token or silently activate users.

Structured online courses use per-lesson progress. A completed stage created before lesson tracking
projects its lessons as completed without rewriting history. Legacy URL-only online stages still
accept stage-level progress, while linked courses require the lesson endpoint. Lessons with recorded
progress cannot be deleted (HTTP 409); archiving the course preserves referential integrity.

Learner quiz submissions return aggregate score/pass/attempt information only: no per-question
correctness, answer key or explanations. The deprecated `questions` array is always empty so
already open older `/learn` clients can still consume the response without exposing answer analysis.
Required previous lessons gate both passing and failed
submissions; the passing threshold is checked before display rounding. In the current legacy
lesson schema, required `quiz`/`test` lessons contribute their best successful scores to the course
stage and individually to the assignment average alongside required scored stages; optional tests
do not contribute. A failed retry preserves a previously passed grade. If the passing threshold changes before the
first success, that first success replaces any higher failed score. Missing grades remain absent,
and certificate PDFs show `Без оценки` instead of inventing 100%. Existing issued scores are unchanged.
This compatibility fix does not implement the planned explicit final/practice/intermediate roles,
immutable course/test versions, exact-ratio score storage or an immutable quiz attempt history.

Quiz submissions, administrative assessment attempts, assignment start and lesson/stage progress
take the same PostgreSQL transaction advisory lock per assignment before their first ORM read.
Concurrent accepted requests therefore see committed attempt counts and best grades, including
when creating the first progress row. A request queued behind assignment completion receives 409
without another attempt, certificate or reward. Different assignment keys do not block each other;
rollback releases the lock. Event selection/attendance and inter-assignment counters remain outside
this guard; the existing event/stage row-lock order is unchanged. All serving backend nodes must
run this guard before the concurrency guarantee applies.

Managed administrative training requests additionally use
`training/access/TrainingAdministrativeAccess`. Assignment, participant, result,
preference and certificate visibility follows the pharmacist's **current** pharmacy,
intersected with the existing regional-manager/trainer restrictions. SQL selects
the permitted IDs before loading child records, computing totals or paging. Shared
program/course authoring and event creation/editing require explicit network-wide
access; a participant's pharmacy grant cannot authorize editing a shared event.
Administrative writes recheck the current authority before domain writes. Publishing
programs/courses and configuring their bonuses require separate exact permissions.
Historical person-wide rewards require the existing global financial permission;
without it, reward payloads are omitted and monetary totals are `null`, not zero.
The managed assignment report is the separate XLSX endpoint; the legacy CSV endpoint
explicitly rejects managed callers and remains compatible for LEGACY accounts.
The separate assignment/result XLSX files retain saved employee names within their
learning scope. Assignment reward amount/status stay blank without global financial
authority and a global report scope; they use saved training rewards, not a wallet
balance or a newly calculated payment. Phones, IINs, passwords, answer keys and private
feedback/competency data remain excluded. The frontend reads exact action flags from
a fresh `/api/admin/access/me.trainingActions` response; an unknown or stale managed
permission never falls back to a role title or the old coarse management flags.
These checks do not change learner authentication, quiz scoring, certificate rules,
mobile endpoints or the legacy HQ read-only ceiling.

Public training verification:

- `GET /api/public/training/certificates/{qrToken}`
- `GET /api/public/training/certificates/{qrToken}/pdf`

### POSM

All POSM endpoints require `X-Posm-Key`.

- `POST /api/posm/recommend`
- `POST /api/posm/recommendations/{eventId}/outcome`
- `POST /api/posm/recommendations/{eventId}/shown` — факт+время показа попапа (V032, атрибуция)
- `POST /api/posm/sales`
- `GET /api/posm/playlists/active?pharmacyId=...`
- `GET /api/posm/app/version`
- `POST /api/posm/heartbeat`
- `GET /api/posm/tasks?pharmacyId=...` — scoped CRM assignment with individual device identity.
- `POST /api/posm/tasks/shown` — accepted/rejected confirmation of an actual displayed QR.
- `POST /api/posm/cdp/lookup`
- `POST /api/posm/cdp/register`

### Shared Public

- `GET /api/health`
- `GET /api/media/img?u=<configured-catalog-image-url>`

The media proxy allows only the exact configured Medusa or enabled website origin, never follows
redirects, and avoids browser mixed-content blocking when a source image uses HTTP.

## Security Model

- Admin auth uses bcrypt passwords, access JWT, hashed refresh tokens, and admin roles.
- Mobile auth uses Daribar OTP and separate ePharm mobile refresh tokens.
- POSM uses a device key; comparison is constant-time.
- `GlobalExceptionHandler` returns JSON `{code,message,fields?}` instead of stack traces.
- Business errors should be `AppException(ErrorCode, message, HttpStatus)`.
- Receipt approve/reject/import and explicit balance changes require HQ, system-admin or finance
  roles. Administrative pharmacist phone/password resets require HQ or system-admin only; the
  service checks these sensitive fields before applying any profile mutations. A password login
  alone is not a monetary executor proof; the separate fresh OTP and HQ affiliation still apply.
- Receipt/photo bucket access is still a hardening topic: current MinIO bucket is public-readable.

## Mobile OTP

The opt-in monetary merchandising flow uses a separate purpose-bound fresh OTP, never a
generic login code or password JWT as proof of phone ownership. Its signed challenge,
opaque grant and immutable wallet contract are described in
[unified pharmacist wallet](22-unified-pharmacist-wallet.md).

`RecommendationSaleBonusService` is a separate opt-in, post-activation bridge from actual
display ACK and exact printed sale to a source-bound pending bonus. It requires a trusted
internal seller, pharmacy/session/product match and bounded timestamps. It never fabricates
an accepted outcome or bypasses HQ receipt moderation; name-only and database-close evidence
cannot trigger it. Both monetary opt-ins default to false in application and production Compose.

The mobile application calls only ePharm endpoints. `OtpProvider` selects the implementation:

- `OTP_DEV_MODE=true`: local fixed code for development/tests; no external request;
- `OTP_DEV_MODE=false`, `OTP_PROVIDER=daribar`: Daribar generates, sends and verifies the code;
- `OTP_PROVIDER=p1sms`: legacy fallback where ePharm generates the code and p1sms only delivers it.

Daribar requests are backend-only: `POST /api/v2/sms` with `sms_type=auth`, followed by
`POST /api/v2/auth` with `validation_code`. Daribar access/refresh tokens are deliberately discarded;
after phone verification ePharm issues its own JWT pair. The database stores the provider id and an
opaque nonce, never the external OTP. Per-phone resend cooldown, TTL and attempt limits remain local.
See `docs/15-daribar-otp.md` for the extracted contract and operations.

## Catalog source and historical snapshots

The current authoritative external catalogue is `https://www.aptekasosklada.kz`
(`STOREFRONT_CATALOG_ENABLED=true`). It supplies product lists, detail, category handles and
campaign price refreshes server-to-server. The remote Medusa origin is retired:
production uses `MEDUSA_ENABLED=false`, and the client also disables its remote requests and crawler
whenever the website source is enabled, even if an older environment still contains a true legacy flag.
This does not rename historical DTOs/packages or delete stored products and campaign references.
A complete local historical snapshot
remains the outage path and preserves historical product IDs referenced by campaigns. Website
product IDs can differ from historical IDs. Viewed website products retain their original handles
and last-known-good payload in `storefront_product_references`, scoped by configured origin, so
campaign price refresh continues after a cold restart. Campaign/rule pricing uses this same source
priority and refreshes the website detail instead of treating its in-memory copy as a fresh price.
If the website fails, campaign refresh uses only the stored product from that same website;
historical data is not written over a website price or barcode. The complete historical snapshot
remains available for read-only catalogue browsing. Missing data preserves existing campaign values. Unknown website-only
IDs fail promptly rather than waiting on the retired origin. No barcode is inferred from a name.
The feature flag and website origin are supplied through production environment variables. If neither
the website nor suitable saved data can satisfy a request, it fails promptly; the retired server is
not a fallback. Historical snapshot availability does not assert current pharmacy stock or freshness.

For the historical source, backend is the only consumer of Medusa from this repo. It fetched:

- product list/detail/category data;
- images, barcodes, MNN/ATC/rx metadata;
- product recommendation data;
- pharmacy stock locations exported into `seed/pharmacies.json`.

Mobile/admin receive normalized DTOs from this backend, not raw Medusa responses.

Important behavior:

- The retained PostgreSQL read model contains the last complete, versioned historical snapshot.
  Its legacy crawler is inactive with the website source enabled. Website outages may use this
  local read model; no request is sent to the retired remote `q` endpoint.
- Search covers product name, brand/manufacturer, MNN, category, SKU, barcode and alternative names.
  A trigram index keeps partial Cyrillic/Latin searches fast. Failed or partial refreshes retain the
  last complete generation and retry automatically.
- `metadata` placeholders such as `-`, `_`, `none`, `n/a`, `н/д` are treated as empty.
- Prices may be missing in Medusa; UI must degrade to "Цена в аптеке".
- Product images may be HTTP; UI should display them through `/api/media/img`.

## POSM Matching

Barcode is the primary key for cash-desk recommendation matching.

`CartItemDto` can include:

- `barcode`/EAN - primary matching key, resolving any confirmed `products.barcodes[]` alias;
- `name` - fallback matching key after normalization;
- `sku` - Standard-N internal `iPartID`, exact fallback when barcode ownership permits it;
- `qty`.

Ambiguous barcode or normalized-name matches are logged and skipped. The system prefers not showing a
recommendation over showing the wrong one.

Campaign/product authoring and catalogue DTOs expose additive `barcodes[]` while retaining the
legacy primary `barcode`. The website adapter joins explicit UUID `WARE_ID` values to the active
ACC snapshot in a batch, with case-insensitive WARE matching and global source-code ambiguity
rejection. It does not infer identity from names or marketplace SKUs. Complete source identities
replace revoked aliases, whereas incomplete/unavailable sources preserve saved keys. Distinct
variant doses/forms/packs are not flattened. A supplied ambiguous barcode blocks name/iPart
fallback, including when its other owner has no active rule. Money attribution additionally
rejects unknown barcodes and conflicting supplied exact identifiers. POSM keeps sending one actual
scan code; `triggerBarcode` in the response stays that actual code, not the first catalogue alias.

## Dashboard Journal

`GET /api/admin/dashboard/recommendations?page=0&size=50` returns KPI values for the configured
analytics window and a server-paginated combined journal of recommendation displays and POS sale
items. Sale JSON arrays are expanded in PostgreSQL before `LIMIT/OFFSET`, so every receipt item is a
separate row and all pages can be traversed without loading the complete sales window into the JVM.
The response includes `page`, `pageSize`, `totalElements`, and `totalPages`. Page size is capped at
100; the legacy `limit` query parameter remains an alias for the first-page size.

## Commands

```bash
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home

./gradlew bootRun
./gradlew test
./gradlew build
```

Local dev needs the root `docker compose up -d` infrastructure.

## Opt-in unified administrative access

`auth/access` connects `auth/policy` to persisted grants and request-bound SQL
scopes. V066 defaults existing accounts to LEGACY; new accounts are MANAGED and
empty-deny. Current admin identity/status/role are reloaded from the database,
while mobile, POSM and HMAC authentication remain separate. Exact handler patterns
are catalogued; unknown/NOT_READY routes deny managed accounts. Existing role
checks remain an additional ceiling. See [the U1/U2 contract](23-unified-admin-data-and-access.md)
for supported surfaces, field projections, management locking and remaining gates.

The local unification candidate adds `GET/PUT /api/admin/promo/{id}/changes` and
`POST /api/admin/promo/{id}/changes/apply`. PUT accepts `expectedRevision` and one
complete `bundle: {campaign, rules}` or explicit `discard: true`; Apply accepts the
saved `expectedRevision`. Author identity comes from the authenticated principal.
Both campaign and recommendation capabilities are required, globally scoped;
financial changes require separate configure-bonus permission. V067 retains pending
JSON separately from published tables and keeps an immutable publication history.
Conflicting revisions/source conditions return409 without partial publication.
These endpoints do not change POSM authentication, paths or response shapes.

The disposable dev reset explicitly truncates pending/publication history together
with its existing fixture-only FK set before reseeding campaigns. It uses no CASCADE
and does not weaken append-only triggers. Both destructive utilities are excluded
when `prod` is active, including a combined `prod+dev` profile. It must not be run
against restored business data; migration rehearsals use plain Flyway without it.

## Native unification candidate (opt-in)

`merch/inventory`, `merch/placements`, `merch/producer/auth`, `merch/tasks` and
`merch/delivery` own the new database-backed workflows. They do not proxy the CRM
administration UI. Before cutover, `merchtasks/MerchTaskClient` serves the legacy
integration. With `MERCH_LEGACY_PORTAL_CUTOVER_ENABLED=true`, its recommendation context
comes only from the owned `merch_recommendation_signals` and `merch_product_balances`
tables; missing native data does not fall back to CRM HTTP. Existing global disable,
response validation and concurrency limits still apply. The optional SELECT uses the
caller's connection, a savepoint and a one-second JDBC timeout so SQL errors or cancellation
do not abort later local recommendation-event writes. Exact active verified pharmacy/branch
scope, barcode/ipart triggers, branch precedence and stock staleness are preserved.
Task stock observations are not authoritative product balances.

`tools/unification/owned_context.py project` preserves source IDs and raw provenance from
the pinned typed CRM snapshot. Its manual `sync` mode retains the reviewed-file workflow:
private input and source/target hashes, dry-run by default, explicit pharmacy count and
context hash for apply, a lock acquired before the snapshot, and immutable input audit.
Stale/conflicting observations and generated-ID collisions across product roles are refused.
It does not schedule refreshes, create identities, issue tasks or replay financial rewards.
`MerchTaskDelivery` chooses one writer for
polling and routes acknowledged native-owned tokens independently of issuance flags.

Native delivery, producer authentication and placement expiry default OFF.
`MERCH_TASK_EVIDENCE_ROOT` and `MERCH_TASK_LEGACY_FILES_ROOT` are private owner-only
absolute directories; missing storage returns503 rather than publishing evidence
in the screens bucket. `/api/public/merch/tasks/**` requires the task capability
header per object. A capability never grants an administrator or pharmacist identity.
Paid recipient confirmation remains a separate purpose-bound proof.

`GET /api/admin/merch/tariffs` and `PUT /api/admin/merch/tariffs/{category}` expose
the supplier tariff matrix to HQ accounts with explicit global merchandising view
and financial access; writes additionally require global edit access. The update
uses the returned `updatedAt` as a compare-and-set token and records immutable
before/after audit entries. Amounts travel as exact integer strings in the full
nonnegative bigint range; the multiplier retains the source range 0.1–10 with
three decimal places. Editing a tariff never rewrites its source snapshot or
reprices saved placement items, submitted requests, rewards or wallet entries.
The UI entry uses fresh `canViewMerchTariffs`; stale edits require a fresh fetch.

`reporting/exports`, `reporting/producers` and `reporting/xlsx` create separate XLSX
workbooks with explicit view/export permissions. Producer associations are explicit
versioned facts, not brand/name heuristics. Wallet reports read postings once and
retain unknown/conflicting historical pharmacy attribution as unknown.

## Learner credential export (D9)

`learnercredentials` owns POST `/api/admin/learner-credentials/exports`, POST
`/{id}/download` and DELETE `/{id}`. Route preflight requires scoped registry view;
the transactional service separately requires explicit HQ2 credential authority, current
password reauthentication and current access/credential versions. The preparatory API
returns metadata only; download returns a no-store one-use XLSX. Ordinary roster exports
never contain credential values. `app.learner-credentials.enabled` defaults false.
Key file, bounded TTL/batch/attempt settings and capture behavior are documented in
[the data/access contract](23-unified-admin-data-and-access.md#d9-current-learner-credential-export).

### Scoped shared staff access (unification candidate)

`/api/admin/merch/staff-access` and `/options` require explicit fresh
`merchandising/staff_access` view/create/revoke grants over every covered branch.
`/api/staff/auth/code|qr` exchanges a bounded monthly code or independent QR for an
opaque12-hour session; only its SHA is stored. No admin JWT, pharmacist identity or
reward is inferred. Manual revoke terminates sessions, including already-consumed
codes, and repeated revoke preserves the first actor/audit. Source code hashes,
branch links and imported auth attempts remain immutable history.

Public `/api/staff/visits` operations validate that session, active organization,
branch/month scope and deadline on each request; mutation checks join the write
transaction before domain locks. Shared attempts carry staff_visit_id plus an
unverified display name and cannot carry PERSON/account/pharmacist/capability
identity. They reuse evidence/review validation, with no bonus path. A returned
subset cannot discard submitted siblings or use rejected photos as new evidence.
Staff sessions and native ephemeral login attempts must never be promoted from a
rehearsal database. `MERCH_STAFF_ACCESS_ENABLED` defaults to false.
