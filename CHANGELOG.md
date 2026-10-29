# Changelog

All notable production changes to Epharm are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and release tags use semantic versioning.

## [Unreleased]

### Added

- Separate D9 learner credential export for explicitly authorised HQ accounts: current-password
  proof, scoped one-use XLSX, version-bound encrypted copy and an external recovery key ring.
- Unified-admin development candidate: seven navigation sections, verified account and grant
  management, native merchandising inventory, producer workspace, placement review, pharmacy task
  attempts/evidence/review, compatible POSM task delivery, and separate scoped Excel reports.
- Offline-only rehearsal tools preserve source rows, identities, capabilities and private evidence;
  native automation and new authentication realms remain opt-in pending migration acceptance.
- Added fail-open merchandising recommendations and structured pharmacy/network/warehouse stock
  context to the existing POSM recommendation flow, while preserving ePharm's canonical rules,
  event attribution and pharmacist balance accounting.
- Completed pharmacist course materials end to end: administrators can publish external links,
  interactive content, downloadable attachments, required lessons and minimum video-view targets;
  the mobile app now renders and opens every published material.
- Added local search and operational sorting to the pharmacist training list.

### Fixed

- POSM 1.0.73 candidate: replace changed receipt rows with a fresh object so quantity,
  price, discount and line amount refresh together with the total after a Standard-N scan.
  The real shared upsert is covered by both log and Firebird lifecycle regression tests.
- Guard expanded-schema recovery against older authorization which cannot enforce managed
  scopes, and preserve native evidence, independent credential keys and exact MinIO policies.
- Prevent a delayed pharmacist course response from reopening a course after navigation away from
  it; preserve the separate Training tab and bank-card placeholder from the latest main.
- Added a bounded mobile API timeout so unavailable services fail with an actionable retry state
  instead of leaving the application waiting indefinitely.
- POSM 1.0.65 spreads healthy QR-task polling over 24–36 seconds per device, reducing
  synchronized load on the merchandising fallback without changing recommendations or orders.

## [0.1.53] - 2026-10-29

### Added

- Separate pharmacist Training navigation for all personally accessible courses, including completed
  assignments, with status, progress and deadline. Home keeps one current available course.
- Honest disconnected bank-card placeholder in the pharmacist profile; the action only explains the
  future feature and never collects payment data, links a card or transfers money.

### Rollout

- Frontend-only release; preserve backend, POSM, training data, balances and the current admin UI.

## [0.1.52] - 2026-10-22

### Fixed

- Unified the pharmacist portal accents in the pharmacy teal palette while keeping the
  administration interface unchanged.
- Highlight correct quiz answers in green after submission and retain a distinct error state
  for an incorrectly selected option.
- Updated the test runner to the patched Vitest 4.1.12 release used by the frontend quality gate.

### Rollout

- Frontend-only release. Preserve the running backend, database, POSM services and administration
  workflows; replace only the deployed web frontend image after creating a verified rollback bundle.

## [0.1.49] - 2026-10-22

### Added

- Added a responsive ePharm rating card beside the pharmacist balance, calculated from real course
  progress and assessment scores with a clear 0–100 scale and status.

## [0.1.48] - 2026-10-21

### Fixed

- Keep the pharmacist's Recommendations button inside the learner portal. Its own
  route displays the active recommendation groups and offers from the self-service
  API, without an HQ login or access to the administration console.
- Reconcile the deployed learner reading flow, navigation and personal-data views
  with current main instead of rolling back campaign authoring, ACC triggers,
  reports, merchandising identity confirmation or the training administration.
- Show personal-data authorization failures explicitly rather than displaying a
  fallback balance; discard responses from an account that has already signed out.

### Rollout

- Frontend-only release with an audited, rollback-capable transaction. Keep the
  running backend v0.1.47, POSM, balances, active rules and integration settings.

## [0.1.47] - 2026-10-21

### Added

- Read-only pharmacist self-service APIs for the canonical personal balance in KZT and
  a paginated directory of active substitution/cross-sell recommendations. Both derive
  identity from a valid ePharm pharmacist access token, reject inactive accounts and
  prohibit shared response caching; caller-supplied user identifiers grant no access.
- Recommendation responses retain exact-product, ACC group, subgroup and INN triggers,
  up to five offers per group, per-offer rewards, scripts and confirmed barcode aliases.
  This directory does not replace POSM cart matching or local stock checks.

### Fixed

- Reject expired, malformed, unsupported and invalid-claim JWTs as authentication errors
  instead of allowing token-parser failures to become server errors.

### Rollout

- Backend-only release: preserve the deployed frontend and POSM artifacts, existing
  pharmacist balances, campaign states and integration settings. No database migration,
  automatic account activation, reward payment or rule publication is included.

## [0.1.42] - 2026-10-21

### Changed

- Temporarily pause employee sign-in on cash desks. POSM 1.0.72 does not start the staff
  module, so no login, registration, recovery or staff-status window/tray is created even
  when an older local configuration enables it. A reviewed new binary is required to resume.
- Keep production `POSM_STAFF_AUTH_ENABLED=false` for clients awaiting the update. Preserve
  the complete staff implementation and stored profiles/password hashes for a later pilot.
- Client-only release: backend/admin remain v0.1.41. Standard-N, receipt delivery, recommendations,
  merchandising QR, customer display and the signed updater are unchanged.

## [0.1.41] - 2026-10-21

### Added

- Visible IIN/JSN column in the pharmacist registry, preserving leading zeroes and credential role restrictions.
- POSM 1.0.71 offers a clear registration/recovery choice after failed sign-in, retaining the typed IIN
  while clearing passwords and SMS state; unknown IIN and wrong password remain indistinguishable.
- Connected-cash-desk diagnostics distinguish staff capability, local session/database failures,
  a visible sign-in window and a confirmed staff grant using fixed non-personal status codes.

### Fixed

- Optional staff-channel initialization and capability errors no longer disappear without a bounded reason.
  Diagnostic input cannot insert arbitrary personal data into heartbeat storage or the admin page.
- A heartbeat from an old client clears an obsolete staff-auth diagnostic rather than implying an
  authenticated employee. These self-reported codes are not authorization or monetary identity.

### Rollout

- Standard-N, Windows, receipt delivery, recommendations, QR and customer display remain independent
  of staff sign-in. Passwords remain bcrypt hashes; existing employees/campaigns are not reseeded.
- Read the live diagnostic after rollout before naming a particular Standard-N compatibility issue;
  server capability success alone does not prove that the cashier session is readable or a person signed in.

## [0.1.40] - 2026-10-21

### Fixed

- Restore exact-product campaign selection when the website omits EANs but supplies an exact
  WARE_ID present in the reviewed active ACC snapshot; never guess from a marketplace SKU or name.
- Retain every confirmed barcode alias through catalogue, campaign creation/editing and refresh;
  any alias triggers the same product rule once, while four trigger modes and per-offer rewards remain.
- Refresh picker metadata even when search returns the same product IDs; display barcode counts
  and reject genuinely unidentified products instead of saving an unusable scan trigger.
- Reject globally ambiguous aliases, retain actual scanned-code popup binding, suppress already
  purchased offers under alternate codes, and revoke removed source identities without clearing
  saved keys during an outage. Different strengths, forms and packs are not merged.
- Attribute journal conversion time to the resolved sale product or confirmed legacy alias,
  never an arbitrary first receipt item. Financial gates and medical-rule statuses are unchanged.
- Apply compatible patched Vite and follow-redirects versions required by the security audit,
  without extending the existing development-only advisory exception or bypassing CI.

### Deployment

- Additive barcode-identity migration; backend/admin update only. Existing POSM clients already
  send the actual scan code and do not require another binary release for this change.

## [0.1.39] - 2026-10-20

### Added

- One atomic pharmacist wallet ledger for approved recommendation/training rewards, audited
  adjustments and payout reservations, preserving existing opening balances without fake history.
- A disabled-by-default CRM reward path: HQ-verified executor, separate fresh OTP, one-use grant,
  immutable task approval and idempotent credit into the same wallet. Viewing a QR never credits money.
- An independently gated real-display plus exact printed-sale bridge that creates one bonus
  reservation for HQ moderation, without invented acceptance or historical backfill.

### Fixed

- Serialize manual and imported receipt decisions so a late rejection or Excel row cannot erase
  a confirmed credit or override a final decision.
- Restrict receipt approval, Excel financial imports and balance adjustments to authorized roles;
  only HQ/system administrators may reset another pharmacist's phone or password.
- Reject legacy backend transitions after financial schema expansion, including automatic failure
  rollback, and test the actual previous binary against the forward schema in CI.

### Deployment

- Deploy all financial migrations together. Monetary bridges remain disabled until paired CRM
  and ePharm CI, exact employee mapping, synchronized clocks, backup/restore and a controlled
  real-phone/manager approval pilot. POSM 1.0.70 does not need a second binary update for these APIs.

## [0.1.38] - 2026-10-20

### Fixed

- POSM 1.0.70 clears the customer receipt immediately on confirmed print and after confirmed
  workstation-bound empty reads, rejects stale database results after newer cashier events,
  and preserves live carts when database/session identity is uncertain.
- Recommendation display acknowledgements require actual non-activating native topmost-window
  evidence instead of a hard-coded success flag; the guard retries without taking scanner focus.
- Expired merchandising deadlines hide QR codes even when a delivery token has not expired.
- CRM recommendations tolerate aligned product identifiers, reject unavailable/unmapped context,
  and preserve local offers on stale stock or contradictory external rules within the 5+5 limit.
- Production catalogue defaults and release checks use aptekasosklada.kz; enabling that website
  prevents legacy Medusa remote calls while retaining historical campaign IDs and local snapshots.
- Exact-product, ACC group, subgroup and INN campaign authoring remains protected by compiled-image
  browser regression tests. Receipt, order, task and recommendation paths remain separate.

## [0.1.37] - 2026-10-16

### Added

- HQ-only campaign PDF/JSON reports distinguish confirmed popup displays, confirmed replacement
  sales, pharmacist actions and the separate 0–120-second conversion, with charts and methodology.
- POSM 1.0.69 adds optional employee IIN/password sign-in, SMS-confirmed pending registration,
  first-password activation and bound-phone password recovery. Standard-N and Windows stay usable.
- Staff sessions are scoped to the individual device, pharmacy and observed cashier context;
  account/device changes revoke grants. Phone-wide OTP requests are serialized across mobile/POSM
  purposes, and failed-attempt limits survive transaction rollback. Transient auth data expires.

### Fixed

- Preserve invalidation of an older SMS challenge even when a provider sends a replacement code
  but its response fails. Coordinate first-password activation with the staff password writer.
- Include main's verified, bounded local backup and rollback-bundle retention contracts.
- Remove disposable restore-test anonymous volumes along with their containers, preventing
  repeated verification from accumulating abandoned database copies on the application disk.

### Rollout

- Use the reviewed main source, not the out-of-band v0.1.36 source label. Existing campaigns and
  financial data are not reseeded. Keep the previous images for application rollback.
- `POSM_STAFF_AUTH_ENABLED` defaults to false. Enable the server for a controlled Windows/SMS
  pilot; promote the signed client to the fleet only after the manual acceptance checklist passes.

## [0.1.35] - 2026-10-15

### Fixed

- Restore the reviewed main application after an out-of-band deployment removed product/ACC
  group/subgroup/INN campaign authoring. Preserve the linked catalogue and existing recommendation data.
- Exercise all four trigger choices and draft request contracts against the final admin image,
  not only the development server; reject the obsolete product-only build before promotion.
- New application promotions require the exact current main commit and its successful trusted CI.
  Explicit recovery/rollback remains separate and does not depend on GitHub availability.

## [0.1.32] - 2026-10-14

### Fixed

- POSM 1.0.68 restores new merchandising assignments after a bounded tray reminder and keeps
  QR windows above the pharmacist desktop without activating them or interrupting barcode input.
- Display receipts survive network failures and restarts in a bounded, DPAPI-protected,
  pharmacy/device-scoped queue; expired/revoked receipts do not retry indefinitely.
- Merchandising rejects private-looking DNS names and credential-forwarding redirects. Metrics
  distinguish accepted receipts from HTTP-success rejections, with dedicated alerts.
- CI, Docker builds and transactional release gates inspect the actual JAR for the protected bridge
  and configuration before accepting a release; exact-commit source remains the release authority.
- CRM lifecycle/media fixes are released from its separate repository with independent provenance.

## [0.1.31] - 2026-10-14

### Fixed

- The impressions-and-sales journal bounds receipt candidates before expanding their items,
  avoiding a full historical item scan for each page while preserving counts, ordering and attribution.
- Journal load failures now show an explicit retry state instead of zero metrics and an empty
  history. Failed refreshes retain the last successful data with a visible stale-data warning.

## [0.1.30] - 2026-10-13

### Fixed

- Campaign and recommendation prices now follow the same configured website catalogue as the
  product picker, preserving historical source fallback and previously saved prices on outages.
- Persisted website product references retain their original handles across backend restarts;
  scheduled price refresh no longer relies on an in-memory catalogue visit or an old Medusa ID.
- The trigger-first campaign form retains exact-product, ACC group, subgroup and INN choices.
  Products without a usable barcode or Standard-N identity are clearly unavailable as initial
  exact-product scan triggers; offered products and broad triggers remain independently selectable.

## [0.1.29] - 2026-10-13

### Fixed

- Restored the Firebird JDBC driver required by the Standard-N pharmacist-directory sync. The
  v0.1.28 backend image omitted it; the previous backend remained available while this fix was
  tested. CI now checks both runtime loading and packaging in the production boot JAR.

## [0.1.28] - 2026-10-13

### Added

- Reconciled the live-course editor, broadcast studio and pharmacist live lesson with the
  versioned backend and frontend. A new forward-only migration permits live lessons on databases
  that already received the course-live schema; stream links require safe HTTPS URLs.
- Preserved the deployed website catalogue source behind its existing production feature flag,
  with the local catalogue snapshot retained for outages and existing campaign product IDs.

### Fixed

- Retained the tested cash-pickup-only POSM order guard, ACC group/subgroup/INN recommendation
  triggers and the reliable screen-video upload flow while reconciling server-only changes.
- Protected completed live-room interactions against deletion and poll-id changes, and serialized
  voting, chat and reactions with lesson edits so concurrent actions cannot orphan audit data.
- Kept post-launch broadcast timestamps immutable in the admin editor and made website category
  filtering use category handles, with bounded failure when only the retired origin is unavailable.

## [0.1.26] - 2026-10-12

### Added

- Campaign creation now starts by choosing the recommendation trigger: exact product, ACC
  group, subgroup or international nonproprietary name. The editor shows the matching
  searchable options while retaining the existing product-trigger flow.

### Fixed

- Screen media uploads use a bounded upload-specific timeout instead of the 15-second API
  default. After an uncertain response, the active broadcast slot is refreshed from the server.
- The isolated backup restore test waits until its target PostgreSQL database accepts a real
  query before restoring, avoiding a startup race.
- Updated the storefront Sharp dependency and audited the reproducible merchandising patch
  dependencies used by the release checks.

## [0.1.25] - 2026-10-06

### Added

- ACC group, subgroup and international nonproprietary name (INN) can be selected as
  recommendation triggers alongside the existing exact-product trigger. The classification
  comes from an explicitly reviewed, versioned ACC catalogue snapshot; ambiguous barcodes
  do not trigger a broad rule, and newly authored broad rules start as drafts.
- Reconciled the live certificate editor, course Excel import, program-cover upload and mobile
  retail-price fallback with the new recommendation code; Kazakh certificate glyphs and image
  size limits are covered by regression tests.
- Added a content-verified two-image transaction for the archive-based production host, including
  automatic backend/frontend rollback and a forward-schema compatibility drill.

### Fixed

- Updated the storefront to a patched Next.js release and refreshed available build-tool
  transitive fixes after newly published dependency advisories.
- The frontend dependency gate now fails on every production advisory and every new audit
  finding. Its one time-limited exception is the unpatched, dev-only `braces` advisory
  inherited through build/lint tooling; it expires on 2026-11-06.

## [0.1.17] - 2026-09-25

### Changed

- Redesigned the pharmacist recommendation window for legible replacement and cross-sell lists,
  with up to five products in each section and mouse-wheel scrolling. POSM 1.0.67 preserves
  scanner focus and the existing recommendation trigger and acknowledgement behavior.
- Administrators can set an optional reward separately for every recommended product. Only
  products with a positive reward receive the green highlight; a zero reward is displayed without
  bonus. Existing rules keep their campaign-level reward unless explicitly changed.

### Fixed

- Preserved the production training portal and code-based event check-in in the versioned source
  before this release, including legacy course access and idempotent attendance checks.

## [0.1.14] - 2026-09-23

### Fixed

- The immutable release smoke gate now waits for backend and frontend to report the candidate
  release after a rolling restart. A transient startup 502 no longer triggers a false rollback;
  stale or unavailable routes still fail within a bounded interval.
- Carries forward the application changes prepared in v0.1.13, whose production attempt was
  automatically rolled back before this readiness correction.

## [0.1.13] - 2026-09-23

### Changed

- Cashiers receive and can act on internet orders only for explicitly selected pickup with cash
  payment; existing delivery, online-paid and demo orders remain available for HQ audit.
- POSM 1.0.66 ships the cashier-side eligibility guard with the same signed auto-update channel;
  its offline cache no longer exposes non-cash or non-pickup orders from older releases.
- Reconciled the production training portal, lesson-progress persistence and SPA caching fix with
  the GitHub mainline so the next deployment preserves the live training experience.

### Fixed

- Kept legacy completed lessons readable and URL-only online stages completable after introducing
  per-lesson tracking; a lesson with recorded progress now returns a clear conflict instead of a
  database error when an administrator tries to delete it.

## [0.1.10] - 2026-09-23

### Fixed

- Corrected the public merchandising QR portal's `/merch/staff` asset, task API and media paths;
  limited the ePharm proxy to staff-only routes and required a trusted transport for its service key.
- Bound the POSM offline fulfillment cache to the pharmacy and device so a reassigned cash desk cannot
  display another pharmacy's cached orders. POSM source is versioned 1.0.64 for a separately signed release.
- Validated POSM installer HTTPS and release trust-anchor settings before completing installation.

### Operations

- Added reproducible checks for preserving merchandising photo sidecars across a frontend build,
  atomic `dist` exchange and rollback, and immutable application release preparation.

## [0.1.9] - 2026-09-22

### Fixed

- Merchandising QR delivery receipts now use an explicit JSON byte body with `Content-Length`,
  keeping the ePharm bridge compatible with the fallback Python service while preserving the
  fail-open isolation of recommendations, heartbeats, sales and other POSM schedules.

## [0.1.8] - 2026-09-22

### Added

- Integrated pharmacy merchandising assignments with POSM through an authenticated, fail-open
  task bridge: a configured pharmacy receives the task QR without blocking recommendations when
  the merchandising service is unavailable.

### Fixed

- Kept a recommendation request alive when zkassa.log and the Standard-N Firebird receipt observe
  the same scan, and retriggered recommendations after a POSM restart with an already-open receipt.
- Anchored the pharmacist popup to the visible Standard-N window and moved the customer advertising
  kiosk to the other monitor, including when Standard-N starts after POSM.
- Bound each multi-item response to its real local trigger and prevented transparent windows from
  being acknowledged as displayed.
- Excluded active promotion rules before their start date and after their end date using the
  Kazakhstan pharmacy calendar.
- Produced each POSM update bridge on Windows CI with its exact source commit and SHA-256 provenance,
  preventing a stale binary from being published under a newer version label.

## [0.1.7] - 2026-09-21

### Added

- Production-grade PostgreSQL and MinIO backup, retention, isolated restore testing and backup metrics.
- Prometheus, Alertmanager, Grafana, exporters and actionable availability/SLO/capacity alerts.
- Sentry integration points for backend, admin frontend and Flutter mobile application.
- Immutable release identity, deploy verification and rollback tooling.
- A k6 workload model for a 500-cash-desk fleet.
- Automated mobile release gates, privacy manifest and deep-link declarations.
- Reproducible pharmacy-specific POSM package generation with exact catalog matching, per-device
  credentials and secret-free rollout manifests.

### Fixed

- Added a stable provisioned POSM device identity so packages prepared before installation can use
  heartbeat, recommendations and signed remote updates without a shared bootstrap key.
- Kept the pharmacist recommendation card fully on-screen at 100-200% Windows scaling and above
  Standard-N without stealing scanner or keyboard focus.
- Changed recommendation `displayed_at` acknowledgement to require a rendered, native-topmost window
  whose physical bounds are verified inside the pharmacist monitor.
- Normalized Medusa products containing several GTINs in one barcode field so promotion campaigns
  can be created and matched by the first canonical EAN.
- Included field-level backend validation details in admin error messages instead of showing only
  the generic “check the data” response.

## [0.1.6] - 2026-09-20

### Fixed

- Replaced the stale `0 pharmacies` campaign counter with the current global active-pharmacy target.
- Added an auditable POSM rollout coverage endpoint and admin warning for unprovisioned pharmacies.
- Made recommendation matching fast and resilient to local cash-register barcodes and safe name variants.
- Restart POSM once after device enrollment so every API immediately uses the new per-device credential.

## [0.1.4] - 2026-09-19

### Fixed

- Replaced the generic promotion-rule save error with field-level, accessible validation and an
  actionable error summary.
- Normalized read-only Medusa product snapshots before saving so external catalog text cannot block
  campaign rule authoring.
- Added conditional validation for campaign goals and comparison rows while keeping barcode,
  iPartID and an unused goal optional.

## [0.1.3] - 2026-09-18

### Fixed

- Replaced slow, failure-prone remote Medusa search with an automatically refreshed PostgreSQL snapshot.
- Kept catalogue browsing and promotion product search available during Medusa outages.
- Added release smoke coverage for snapshot readiness and exact-name catalogue search.

## [0.1.2] - 2026-09-18

### Fixed

- Restored secure per-device POSM presence and the live register count during the credential migration.

## [0.1.1] - 2026-09-18

### Fixed

- Restored the live Medusa catalogue for admin, mobile and promotion workflows.
- Reduced Medusa listing latency, added last-known-good cache fallback and hourly linked-product refresh.
- Added release smoke gates that reject an empty production catalogue.

## [0.1.0] - 2026-09-08

### Added

- Initial versioned baseline for the existing backend, admin, mobile and POSM applications.
