# Architecture

## Runtime Shape

```text
                         public internet / VPN
                                  |
                                  v
                   https://epharm.inkar.kz
                                  |
                               Caddy
              /api/* ------------+------------- /s3/* ------------ /
                |                                |                  |
             backend                          MinIO             frontend
        Kotlin/Spring Boot                  S3-compatible      nginx + React
                |
       +--------+---------+----------------+
       |                  |                |
   PostgreSQL           Redis          external Medusa
   Flyway V001-V036     cache/session   catalog/images/barcodes/pharmacies
```

Caddy is the only public entrypoint in the current deployment. Backend, frontend, MinIO, Postgres,
and Redis are internal Docker services. The same host serves API, admin, and S3 by path.

There is also an optional internal VPN hostname in `Caddyfile` (`inkpim.inkar.kz`) that proxies to the
same frontend over plain HTTP inside the corporate VPN.

## API Surfaces

The backend is one Spring Boot monolith with three main API surfaces:

| Surface    | Prefix           | Client                  | Auth                                                                                         |
| ---------- | ---------------- | ----------------------- | -------------------------------------------------------------------------------------------- |
| Admin API  | `/api/admin/**`  | HQ web admin            | Admin JWT + role checks                                                                      |
| Mobile API | `/api/mobile/**` | Flutter pharmacist app  | Public for catalog/promotions/banners/pharmacies/auth; pharmacist JWT for `/me` and receipts |
| POSM API   | `/api/posm/**`   | C#/WPF cash-desk client | `X-Posm-Key` device key                                                                      |

Public helper endpoints include `/api/health` and `/api/media/img` for safe HTTPS proxying of
allowed Medusa HTTP images.

## Main Business Flows

### Campaign -> Mobile Feed -> Receipt

1. Admin creates a Promo campaign and selects a Medusa product.
2. Backend snapshots product name/image/barcode and stores campaign dates, tiers, goal, and rules.
3. Mobile reads `GET /api/mobile/promotions` and renders active campaigns.
4. Pharmacist taps bonus CTA or uses the scan FAB, uploads a receipt photo, and optionally carries a
   claimed promo id.
5. Backend stores the photo in MinIO and creates a receipt with `pending`/review state.
6. Admin Reconcile approves/rejects the receipt; approval credits the pharmacist balance.

### Cash Desk Recommendation -> Deferred Bonus -> Reconciliation

1. The pharmacist signs into Standard-N on the pharmacy workstation using the normal company flow.
2. POSM identifies the local open `zkassa` session and reads its active Firebird receipt from
   `DOCS` + `DOC_DETAIL_ACTIVE`; detailed `Add2Cheque` log lines remain a compatibility fallback.
3. POSM obtains the manufacturer barcode/EAN, product name, internal `iPartID`, price, quantity, and
   the active Standard-N cashier id/name from that workstation-bound session and receipt.
4. POSM sends the cart to `POST /api/posm/recommend`.
5. Backend preserves raw Standard-N identity and maps it to an active internal pharmacist only by
   a deterministic same-pharmacy id or unique full-name match.
6. Backend resolves products primarily by barcode, then by normalized name. Ambiguous barcode/name
   matches are rejected rather than guessed.
7. Rules Engine returns up to two recommendations from active campaign rules.
8. POSM shows the recommendation on the pharmacist screen.
9. POSM later sends sale data to `POST /api/posm/sales`; managers can also import Standard-N Excel
   data via admin.
10. Reconcile service compares POS sale, Excel row, and mobile/manual evidence:

- both POS and Excel match -> auto approved;
- one source only -> manual moderation;
- conflict/duplicate -> flagged/manual;
- no evidence after expiry -> expired.

### Screens / Broadcast

1. Admin Screens section manages 12 independent video slots in grid or list mode.
2. Backend stores media in MinIO and exposes filled slots as one active playlist ordered 1 -> 12.
3. POSM clients poll `GET /api/posm/playlists/active?pharmacyId=...`.
4. POSM downloads changed media into its local cache and atomically switches to the new cyclic
   playlist. Empty slots are skipped and the previous cache keeps playing while offline.
5. Customer screen updates after the next poll/download. SSE screen modes were explicitly cancelled;
   polling is the current design.

### Mobile Auth

1. Phone -> `POST /api/mobile/auth/sms/request`.
2. OTP verify -> `POST /api/mobile/auth/sms/verify`.
3. New user registration -> `POST /api/mobile/auth/register`.
4. Tokens are stored in `flutter_secure_storage`; refresh uses `/api/mobile/auth/refresh`.

In production, `OTP_PROVIDER=daribar` delegates both code generation/delivery and verification to
Daribar. The mobile app never calls Daribar directly and never contains SMS credentials. It submits
the phone/code to ePharm, which issues its own JWT after Daribar confirms the code. The fixed
`5445` value exists only when `OTP_DEV_MODE=true` in local/test environments.

### Training Program -> Assignment -> Certificate

1. Training manager creates a program and separate route for each enabled format.
2. Backend snapshots the published route into an immutable program version.
3. HQ assigns that version to one or more pharmacists with a format, deadline and optional event.
4. Mobile shows only the authenticated pharmacist's route and allows material progress, eligible
   event selection and QR attendance; it never allows format or score changes.
5. Backend combines trusted online results and confirmed attendance, calculates completion and
   creates one certificate and one reward idempotently.
6. Admin dashboard, pharmacist profile and CSV export read the same assignment history.

The complete contract and current MVP boundaries are documented in `13-training-module.md`.

## External Integrations

| Integration  | Current use                                                                                                                                             |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Medusa       | Product catalog, product images, barcode, category metadata, pharmacy stock locations. Backend proxies it; mobile/admin do not talk to Medusa directly. |
| MinIO        | Receipt photos, screen media, APK/demo files. Current bucket is public-readable; privatizing receipt access remains a release hardening item.           |
| Redis        | Infrastructure dependency; available for cache/rate-limit/session work.                                                                                 |
| SMS provider | Daribar gateway via backend-only `/api/v2/sms` and `/api/v2/auth`; dev OTP is local/test only.                                                          |
| OCR / OFD QR | Removed/cancelled by product decision. Receipt validation is POS log + Excel + manual moderation/photo evidence.                                        |

## Design Direction

The current brand UI is Claude-style coral/white:

- primary coral token is still named `brand-green-*` for compatibility;
- former `brand-blue-*` is now a darker coral accent;
- green remains only for semantic success/approved states;
- mobile uses coral as hero surfaces; admin uses coral as sparse accent on a quiet cream canvas.
