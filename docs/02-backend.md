# Backend

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

POSM staff identity is an independent optional API under `/api/posm/staff`: capabilities,
login, OTP request, registration, password reset/activation, session validation and logout.
Every route requires a non-legacy individual `X-Posm-Key`; opaque staff grants also bind
the pharmacy/device and observed process/cashier context. Pending registration does not
assign or activate a pharmacist. `POSM_STAFF_AUTH_ENABLED=false` is the safe default.
Neither this flag nor a failed login gates Standard-N, recommendation delivery or sales.
See [manual staff acceptance](rollout/rps-auth-test.md).

OTP issuance/verification serializes Daribar's phone-wide codes across mobile and POSM.
New staff challenges are purpose/account/device-bound and one-use; independent transactions
preserve invalidation and attempt budgets on request errors. Changing staff status/pharmacy/
password or device credentials revokes grants. Password reset also revokes mobile refresh
tokens; previously issued mobile access JWTs retain their existing short lifetime until expiry.
Transient expired staff challenges/sessions/rate counters are purged after 24 hours.

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
correctness, answer key or explanations. Required previous lessons gate both passing and failed
submissions; the passing threshold is checked before display rounding. In the current legacy
lesson schema, required `quiz`/`test` lessons contribute their best successful scores to the course
stage and individually to the assignment average alongside required scored stages; optional tests
do not contribute. A failed retry preserves a previously passed grade. If the passing threshold changes before the
first success, that first success replaces any higher failed score. Missing grades remain absent,
and certificate PDFs show `Без оценки` instead of inventing 100%. Existing issued scores are unchanged.
This compatibility fix does not implement the planned explicit final/practice/intermediate roles,
immutable course/test versions, exact-ratio score storage or an immutable quiz attempt history.

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
