# Database

Database: PostgreSQL 16.

Migrations: Flyway files in `admin-panel/backend/src/main/resources/db/migration/`.

Current migration range: V001-V036.

## Migrations

| Version | File                                 | Purpose                                                                      |
| ------- | ------------------------------------ | ---------------------------------------------------------------------------- |
| V001    | `init`                               | Flyway/bootstrap marker.                                                     |
| V002    | `auth`                               | Admin users and admin refresh tokens.                                        |
| V003    | `catalog`                            | Internal products.                                                           |
| V004    | `rules`                              | Rules engine tables and JSON trigger/recommend data.                         |
| V005    | `promo`                              | Promo campaigns.                                                             |
| V006    | `pharmacies`                         | Chains and pharmacies.                                                       |
| V007    | `pharmacists`                        | Pharmacist registry, balance, status.                                        |
| V008    | `payouts`                            | Payout batches and items.                                                    |
| V009    | `lms`                                | Courses.                                                                     |
| V010    | `screens`                            | Playlists and slides.                                                        |
| V011    | `ai_exam`                            | AI exam question bank.                                                       |
| V012    | `receipts`                           | Receipts and pending bonuses.                                                |
| V013    | `posm_recommendations`               | Recommendation events and initial POSM mapping.                              |
| V014    | `posm_sales_excel`                   | POS sales, Excel imports/rows, source columns.                               |
| V015    | `cdp_profiles`                       | POSM CDP/customer profiles.                                                  |
| V016    | `playlist_target`                    | Per-pharmacy playlist targeting.                                             |
| V038    | `targeted_screen_playlists`          | Parent/child playlist inheritance and pharmacy-profile assignments.          |
| V017    | `app_releases`                       | POSM app releases and auto-update metadata.                                  |
| V018    | `rule_card`                          | Rich recommendation card JSON and product volume.                            |
| V019    | `mobile_auth`                        | Mobile OTPs, mobile refresh tokens, self-register fields.                    |
| V020    | `drop_ocr_score`                     | Remove OCR score; OCR is not used.                                           |
| V021    | `drop_qr_raw`                        | Remove QR/OFD raw field; QR/OFD is not used.                                 |
| V022    | `promo_products`                     | Campaign product link, dates, tiers, product snapshot.                       |
| V023    | `promo_feed_index`                   | Indexes for mobile promo feed.                                               |
| V024    | `receipt_claimed_promos`             | Claimed promo ids on receipts.                                               |
| V025    | `campaign_product_rules`             | Campaign rule link and override image/description fields.                    |
| V026    | `analytics_indexes`                  | Analytics/performance indexes.                                               |
| V027    | `promo_override_characteristics`     | Product/card override characteristics.                                       |
| V028    | `promo_campaign_goal`                | Campaign goal fields.                                                        |
| V029    | `banners`                            | Mobile banners managed from admin.                                           |
| V030    | `product_barcode`                    | Product/promo barcode fields and removal of obsolete `product_pos_codes`.    |
| V031    | `product_ipart_id`                   | Optional Standard-N iPartID snapshot for deterministic diagnostics/matching. |
| V032    | `recommendation_sale_attribution`    | Show-to-sale correlation, display time, attributed sale and duration.        |
| V033    | `pos_sale_pharmacist_name`           | Standard-N pharmacist/cashier name snapshot on POS sales.                    |
| V034    | `pos_sale_pharmacist_identity_audit` | Raw Standard-N id/name and identity-resolution source on POS sales.          |
| V035    | `training_programs`                  | Programs, versions, assignments, events, attendance, certificates/rewards.   |
| V036    | `training_routes_and_assessments`    | Per-format routes, result history and assignment format history.             |

## Domain Tables

| Domain              | Main tables                                                                    |
| ------------------- | ------------------------------------------------------------------------------ |
| Auth                | `admin_users`, `refresh_tokens`, `mobile_otps`, `mobile_refresh_tokens`        |
| Catalog/rules       | `products`, `rules`                                                            |
| Promo               | `promos` plus campaign rule references                                         |
| Receipts/reconcile  | `receipts`, `pending_bonuses`, `pos_sales`, `excel_imports`, `excel_sale_rows` |
| Pharmacies          | `chains`, `pharmacies`                                                         |
| Pharmacists         | `pharmacists`                                                                  |
| Finance             | `payout_batches`, `payout_items`                                               |
| Screens             | `playlists`, `slides`, `playlist_pharmacy_assignments`                         |
| POSM                | `recommendation_events`, app release tables, CDP tables                        |
| LMS/AI              | `courses`, `exam_questions`, `training_programs`, versions and result history  |
| Training operations | `training_assignments`, stages, events, participants, certificates, rewards    |
| Banners             | `banners`                                                                      |

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
