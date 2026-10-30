# Database

Database: PostgreSQL 16.

For admin unification the two source databases were restored separately: ePharm uses 16,
CRM uses 18.6/dump 1.16. Do not restore the latter directly over the former. The local
identity mapping stays offline; V066 adds opt-in access tables without converting existing accounts; see [the U1/U2 contract](23-unified-admin-data-and-access.md)
and [restore evidence](reports/unification-2026-10-29/isolated-restore-verification.json).

Migrations: Flyway files in `admin-panel/backend/src/main/resources/db/migration/`.

Current migration range in source: V001-V067. V066 and V067 were applied only to the isolated
unification candidate. Neither has been deployed by this task. All original business-table
column, primary-key and row-count fingerprints survived the upgrade; Flyway history gained
only the two expected rows. See [candidate-schema-upgrade.json](reports/unification-2026-10-29/candidate-schema-upgrade.json).

V067 adds prepared campaign changes, immutable publication history and a retirement marker
for rules. Removed campaign rules remain archived with their original IDs so recommendation
events and reports keep their links. The history trigger rejects UPDATE and DELETE. Pending
JSON is an authoring projection; POSM continues to read the published campaign/rule tables.
V066/V067 are now frozen after this candidate rehearsal; subsequent schema fixes require a
new migration. The inert CRM staging schema is not an application read model or a completed
business migration.

## Migrations

| Version | File                                   | Purpose                                                                                        |
| ------- | -------------------------------------- | ---------------------------------------------------------------------------------------------- |
| V001    | `init`                                 | Flyway/bootstrap marker.                                                                       |
| V002    | `auth`                                 | Admin users and admin refresh tokens.                                                          |
| V003    | `catalog`                              | Internal products.                                                                             |
| V004    | `rules`                                | Rules engine tables and JSON trigger/recommend data.                                           |
| V005    | `promo`                                | Promo campaigns.                                                                               |
| V006    | `pharmacies`                           | Chains and pharmacies.                                                                         |
| V007    | `pharmacists`                          | Pharmacist registry, balance, status.                                                          |
| V008    | `payouts`                              | Payout batches and items.                                                                      |
| V009    | `lms`                                  | Courses.                                                                                       |
| V010    | `screens`                              | Playlists and slides.                                                                          |
| V011    | `ai_exam`                              | AI exam question bank.                                                                         |
| V012    | `receipts`                             | Receipts and pending bonuses.                                                                  |
| V013    | `posm_recommendations`                 | Recommendation events and initial POSM mapping.                                                |
| V014    | `posm_sales_excel`                     | POS sales, Excel imports/rows, source columns.                                                 |
| V015    | `cdp_profiles`                         | POSM CDP/customer profiles.                                                                    |
| V016    | `playlist_target`                      | Per-pharmacy playlist targeting.                                                               |
| V017    | `app_releases`                         | POSM app releases and auto-update metadata.                                                    |
| V018    | `rule_card`                            | Rich recommendation card JSON and product volume.                                              |
| V019    | `mobile_auth`                          | Mobile OTPs, mobile refresh tokens, self-register fields.                                      |
| V020    | `drop_ocr_score`                       | Remove OCR score; OCR is not used.                                                             |
| V021    | `drop_qr_raw`                          | Remove QR/OFD raw field; QR/OFD is not used.                                                   |
| V022    | `promo_products`                       | Campaign product link, dates, tiers, product snapshot.                                         |
| V023    | `promo_feed_index`                     | Indexes for mobile promo feed.                                                                 |
| V024    | `receipt_claimed_promos`               | Claimed promo ids on receipts.                                                                 |
| V025    | `campaign_product_rules`               | Campaign rule link and override image/description fields.                                      |
| V026    | `analytics_indexes`                    | Analytics/performance indexes.                                                                 |
| V027    | `promo_override_characteristics`       | Product/card override characteristics.                                                         |
| V028    | `promo_campaign_goal`                  | Campaign goal fields.                                                                          |
| V029    | `banners`                              | Mobile banners managed from admin.                                                             |
| V030    | `product_barcode`                      | Product/promo barcode fields and removal of obsolete `product_pos_codes`.                      |
| V031    | `product_ipart_id`                     | Optional Standard-N iPartID snapshot for deterministic diagnostics/matching.                   |
| V032    | `recommendation_sale_attribution`      | Show-to-sale correlation, display time, attributed sale and duration.                          |
| V033    | `pos_sale_pharmacist_name`             | Standard-N pharmacist/cashier name snapshot on POS sales.                                      |
| V034    | `pos_sale_pharmacist_identity_audit`   | Raw Standard-N id/name and identity-resolution source on POS sales.                            |
| V035    | `training_programs`                    | Programs, versions, assignments, events, attendance, certificates/rewards.                     |
| V036    | `training_routes_and_assessments`      | Per-format routes, result history and assignment format history.                               |
| V037    | `dashboard_journal_pagination_indexes` | Indexes for the paginated analytics journal.                                                   |
| V038    | `targeted_screen_playlists`            | Parent/child playlist inheritance and pharmacy-profile assignments.                            |
| V039    | `mobile_otp_provider`                  | Records which provider owns verification for each OTP request.                                 |
| V040    | `lms_course_lessons`                   | Editable LMS lessons and uploaded video lesson metadata.                                       |
| V041    | `pos_sale_receipt_capture`             | Standard-N document/capture metadata and pharmacy/document lookup index.                       |
| V042    | `pos_sale_fiscal_artifact_metadata`    | Exact fiscal artifact manifest, hash and trusted-source metadata.                              |
| V043    | `order_fulfillment`                    | Storefront order queue, mappings, devices, audit and monotonic update feed.                    |
| V044    | `lms_lesson_attachments`               | Uploaded lesson handouts and media, already established in production.                         |
| V045    | `standardn_pharmacist_mapping`         | Explicit pharmacy/external USER_ID mapping plus immutable change audit.                        |
| V046    | `signed_posm_update_manifest`          | ECDSA manifest signature stored with every POSM release.                                       |
| V047    | `fulfillment_payment_authority`        | Claimed vs trusted payment status and server-side payment authority.                           |
| V048    | `medusa_catalog_snapshot`              | Complete read-model generation for the Medusa product catalogue.                               |
| V049    | `lms_extended_lesson_materials`        | Extended lesson content and materials.                                                         |
| V050    | `training_lesson_progress`             | Per-lesson pharmacist progress.                                                                |
| V051    | `offline_event_check_in_code`          | Offline-event attendance code.                                                                 |
| V052    | `event_participant_code_method`        | Attendance verification method.                                                                |
| V053    | `pharmacist_password_login`            | Pharmacist password-login support.                                                             |
| V054    | `course_quizzes`                       | Course assessment questions and attempts.                                                      |
| V055    | `standardn_pharmacist_directory`       | Standard-N cashier directory.                                                                  |
| V056    | `training_certificate_branding`        | Certificate branding fields already applied in production.                                     |
| V057    | `acc_recommendation_taxonomy`          | Versioned ACC barcode classes and concrete recommendation trigger name.                        |
| V058    | `course_live_lessons`                  | Live-room metadata, messages, poll votes and reactions; already applied in production.         |
| V059    | `allow_live_course_lessons`            | Expand the lesson-kind constraint to include live lessons without editing V058.                |
| V060    | `storefront_product_references`        | Origin-scoped website product handles/payload for restart-safe campaign pricing.               |
| V061    | `posm_staff_auth`                      | Purpose-bound SMS challenges, hashed staff grants, rate budgets and grant-revocation triggers. |
| V062    | `pharmacist_wallet_ledger`             | Append-only opening checkpoints and idempotent atomic credits/payout reservations.             |
| V063    | `merch_verified_executor_rewards`      | Isolated OTP proofs, hashed grants, immutable executor bindings and approved-reward events.    |
| V064    | `recommendation_sale_bonus_source`     | Verified sale source and duplicate-reservation protection for recommendation rewards.          |
| V065    | `product_barcode_aliases`              | Explicit product barcode aliases retained across catalogue refreshes.                          |
| V066    | `unified_admin_access`                 | Opt-in managed access profiles, verified people and transactional access audit.                |
| V067    | `campaign_publication_changes`         | Prepared revisions, immutable publication history and retained archived rule identities.       |

## Domain Tables

| Domain              | Main tables                                                                                                                                                                                                |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Auth                | `admin_users`, `refresh_tokens`, `mobile_otps`, `mobile_refresh_tokens`                                                                                                                                    |
| Catalog/rules       | `products`, `rules`, `acc_catalog_snapshots`, `acc_catalog_state`, `acc_catalog_barcodes`                                                                                                                  |
| Promo               | `promos` plus campaign rule references                                                                                                                                                                     |
| Receipts/reconcile  | `receipts`, `pending_bonuses`, `pos_sales`, `excel_imports`, `excel_sale_rows`                                                                                                                             |
| Pharmacies          | `chains`, `pharmacies`                                                                                                                                                                                     |
| Pharmacists         | `pharmacists`                                                                                                                                                                                              |
| Finance             | `payout_batches`, `payout_items`, `pharmacist_wallet_checkpoints`, `pharmacist_wallet_entries`, `merch_executor_otp_challenges`, `merch_executor_grants`, `merch_executor_bindings`, `merch_reward_events` |
| Screens             | `playlists`, `slides`, `playlist_pharmacy_assignments`                                                                                                                                                     |
| POSM                | `recommendation_events`, app release tables, CDP tables                                                                                                                                                    |
| LMS/AI              | `courses`, `course_live_messages`, `course_live_poll_votes`, `course_live_reactions`, `exam_questions`, `training_programs`, versions and result history                                                   |
| Training operations | `training_assignments`, stages, events, participants, certificates, rewards                                                                                                                                |
| Banners             | `banners`                                                                                                                                                                                                  |

## Current Architectural Decisions

- Applied migrations are immutable. Add a new migration instead of editing an old one.
- OCR/QR fields were intentionally removed. Do not reintroduce them without a product decision.
- Barcode/EAN is the POSM matching key. `product_pos_codes` was dropped because Standard-N `iPartID`
  is not a stable product id.
- `promos.tiers` and rules/card fields use JSONB for campaign-specific flexible data.
- Real pharmacies are seeded from a Medusa-derived JSON snapshot, not generated demo-only data.
- Test profile uses Testcontainers and avoids live Medusa calls.
- Raw Standard-N id/name stays on `pos_sales` for audit. A trusted internal pharmacist is resolved
  only by a deterministic same-pharmacy match.
- `pos_sales.items` keeps the original iPartID/EAN/name and the backend-resolved internal
  `productId`; unresolved or ambiguous catalog matches remain `null` instead of being guessed.
- `source_document_id` is the local Standard-N `DOCS.ID`, not an official fiscal receipt number.

## ACC classification snapshot for recommendation triggers

The user-supplied `Каталог АСС.xlsx` is the only available source for group, subgroup and INN
membership. It is **not** a live price/stock source. The private offline preparer
`tools/prepare-acc-recommendation-catalog.py` validates the workbook columns and generates a
transactional PostgreSQL import plus a SHA-256/count manifest into a new owner-only output directory.
Neither the original workbook nor the generated SQL belongs in Git or a public build artifact.
Run the generated SQL only after Flyway V057 has completed, with `psql` error-stop enabled and a
verified database backup. An import retains every distinct `WARE_ID`/barcode row, then atomically
switches the singleton active-snapshot pointer; repeating the same source SHA is idempotent.

Scope keys are deterministic hashes of normalized labels; a subgroup key includes its parent
group. A barcode linked to more than one `WARE_ID` is ambiguous even if the rows appear to share
the same class, and is excluded from broad-trigger matching. Blank/placeholder group or INN values
do not become selectable scopes. Exact-product rules remain independent of this snapshot. Before
activating a broad rule, check its scope and offered products clinically, then use a real pharmacy
EAN scan for acceptance; internal-only Standard-N codes cannot establish ACC membership. The
source-quality counts and known freshness limitation are in
`docs/reports/2026-10-06-acc-classification-quality.md`.

## Native candidate migrations and source materialization

V068–V074 introduce owned inventory/placements, source identity references and
producer associations, task evidence/history, separate producer sessions/profile
and opt-in native QR delivery. They have been applied only to the pinned isolated
candidate and are immutable. Original CRM rows remain in typed staging with full
checksums; owned projections preserve source UUIDs, payloads and explicit pharmacy
links. Unmatched CRM branches are MERCH_BRANCH resources rather than invented
ePharm pharmacies.

`tools/unification/owned_merch.py`, `owned_tasks.py` and `owned_delivery.py` require
pinned artifacts and a network-isolated PostgreSQL16 candidate. Each runs a single
transaction and verifies a repeat as a no-op. Import never runs source functions,
issues a QR, replays delivery receipts, promotes an account or credits a wallet.
Source in-progress/submitted history is not fabricated into a verified native
attempt. Runtime media are copied privately and verified independently.

The disposable dev reset refuses a candidate containing import evidence, native
tasks or preserved identity/producer links before any destructive SQL. Production
profiles still exclude the reset utilities. Aggregate evidence is in
[wave3-native-merch-verification.json](reports/unification-2026-10-29/wave3-native-merch-verification.json).

V075–V086 have also been rehearsed with plain Flyway on that isolated candidate;
they are immutable there. The latest additions retain private placement-photo
versions, authored comments, template receipts, unique branch/month/PERSON visits,
append-only tariff audits and a composite task-to-photo/placement-item FK. A new
weekly or monthly task pins an exact private photo version; old task references
are not backfilled or rewritten. None of these migrations starts a worker or
replays a wallet operation. `owned_extended.py` preserves exact commercial fields
and explicitly refuses nonempty legacy photo/material/comment sets until their
private-file/author lineage is separately mapped. The current pinned sets are empty.

V085/V086 extend only the placement-reference and inventory-media MIME checks to
WebP. They do not rewrite files or source rows. Original reference, inventory and
task evidence bytes and hashes remain authoritative; a generated XLSX thumbnail
may be JPEG so spreadsheet clients do not need native WebP support.

V087 adds the D9 password vault, encrypted short-lived export payloads, bounded attempt
accounting and append-only metadata audit. It neither reconstructs old hashes nor resets
accounts, starts workers or replays bonuses. Password/hash version mismatch makes the
copy unavailable; the encryption key ring is external to DB backups. A recovery drill
must restore the matching DB and independent key artifact before decryption is accepted.

V090 retains shared monthly staff access independently of personal `/learn`
visits:10 codes,530 branch links and7 imported login-attempt records in the
reviewed source snapshot. `owned_staff_access.py` is the eighth owned projection,
requiring exact source and candidate pins and an exact no-op replay. It refuses
nonempty live source sessions/visit reports rather than discarding them. Imported
code provenance and auth-attempt history are immutable; native sessions and visits
remain empty during import. New shared attempts carry an explicit staff-visit
reference and unverified name, never a fabricated PERSON or wallet recipient.
The090 promotion allowlist has76 native tables; all original72 business tables
remain outside its COPY set. Old088/089 profiles are historical and must not be
used to promote a090 candidate.
