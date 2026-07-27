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
   Flyway V001-V034     cache/session   catalog/images/barcodes/pharmacies
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

1. Admin Screens section uploads or replaces the current broadcast video/image.
2. Backend stores media in MinIO and makes a single active broadcast playlist.
3. POSM clients poll `GET /api/posm/playlists/active?pharmacyId=...`.
4. Customer screen updates on the next poll. SSE screen modes were explicitly cancelled; polling is the
   current design.

### Mobile Auth

1. Phone -> `POST /api/mobile/auth/sms/request`.
2. OTP verify -> `POST /api/mobile/auth/sms/verify`.
3. New user registration -> `POST /api/mobile/auth/register`.
4. Tokens are stored in `flutter_secure_storage`; refresh uses `/api/mobile/auth/refresh`.

`OTP_DEV_MODE=true` means the accepted code is `544544`. Real SMS integration is still an ops/product
decision.

## External Integrations

| Integration  | Current use                                                                                                                                             |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Medusa       | Product catalog, product images, barcode, category metadata, pharmacy stock locations. Backend proxies it; mobile/admin do not talk to Medusa directly. |
| MinIO        | Receipt photos, screen media, APK/demo files. Current bucket is public-readable; privatizing receipt access remains a release hardening item.           |
| Redis        | Infrastructure dependency; available for cache/rate-limit/session work.                                                                                 |
| SMS provider | Not connected. Dev OTP is active in pilot/demo flows.                                                                                                   |
| OCR / OFD QR | Removed/cancelled by product decision. Receipt validation is POS log + Excel + manual moderation/photo evidence.                                        |

## Design Direction

The current brand UI is Claude-style coral/white:

- primary coral token is still named `brand-green-*` for compatibility;
- former `brand-blue-*` is now a darker coral accent;
- green remains only for semantic success/approved states;
- mobile uses coral as hero surfaces; admin uses coral as sparse accent on a quiet cream canvas.
