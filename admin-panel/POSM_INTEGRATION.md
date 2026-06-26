# POSM Integration

Current POSM implementation is C#/WPF in `App/`, not Electron.

## Architecture

```text
Standard-N cash desk
  writes cp1251 zkassa.log
      |
      v
C#/WPF POSM client
  - tail log
  - parse barcode/name/price/qty
  - show pharmacist popup
  - mirror receipt/broadcast on customer display
  - local SQLite outbox
      |
      v
Backend /api/posm/**
  - recommend
  - outcome
  - sales
  - playlists
  - heartbeat
  - app version
  - CDP lookup/register
      |
      v
Admin
  - Promo/rules
  - Reconcile
  - Screens
  - Finance
```

## Transport

- Recommendations are synchronous REST with a short timeout. If backend is unavailable, the cash desk
  continues and no popup is shown.
- Outcomes and sale reports go through local SQLite outbox and retry.
- Customer display media uses polling via `GET /api/posm/playlists/active`.
- Online cash desk count uses `POST /api/posm/heartbeat`.
- SSE/websocket screen control is not active.

## Authentication

All `/api/posm/**` endpoints require:

```http
X-Posm-Key: <device key>
```

The current backend validates against configured `app.posm.device-key` / `POSM_DEVICE_KEY`.
Per-device keys are a hardening item.

## Recommendation Matching

Current matching is barcode-first.

Request item:

```json
{
  "sku": "80309",
  "barcode": "4603423004936",
  "name": "Аквалор",
  "qty": 1
}
```

Rules:

1. Match product by exact `products.barcode`.
2. If no barcode, match by normalized name.
3. If multiple products match the same barcode/name, skip match and log a warning.
4. `sku`/`iPartID` is diagnostic only.

This replaced the obsolete `product_pos_codes` approach; the table was dropped in V030.

## Recommendation Flow

1. POSM reads `Add2Cheque` line.
2. POSM updates checkout session and sends cart to `/api/posm/recommend`.
3. Backend filters active campaign rules and returns recommendations.
4. POSM displays the recommendation on the pharmacist screen (informational popup; no
   accept/skip key — the previous `F9`/`Esc` actions were removed). On display, POSM enqueues a
   durable "shown" ping to `/api/posm/recommendations/{eventId}/shown` carrying the client display
   time (`displayed_at`).
5. POSM sends the printed sale to `/api/posm/sales`.
6. Reconcile matches sale/Excel/mobile evidence and credits bonus on approval —
   fulfillment is attributed from the real sale, not a key press in the popup.

The outcome endpoint (`/api/posm/recommendations/{eventId}/outcome`) still exists on the
backend and is supported by the outbox for backward compatibility, but the current client
no longer calls it from the popup.

## Recommendation → Sale Attribution (V032)

Analytics-only attribution of a shown recommendation to the subsequent sale of the recommended
product, with timing. No bonus/payout side effects.

- When a sale is recorded, the backend correlates it to shown recommendations of the **same
  `session_id`** (one session = one checkout — stronger than a time window) and checks whether the
  recommended product (matched by our `productId`, the same collision-safe iPartID→barcode→name
  resolver used elsewhere) is present in the sale.
- On a match the `recommendation_events` row gets `sold_at` (= printed time), `sale_id`, and
  `seconds_to_sale` = `sold_at − COALESCE(displayed_at, shown_at)` (clamped ≥ 0).
- The "shown" ping (`displayed_at`) is the time origin when present; otherwise the generation time
  (`shown_at`) is used. The ping rides the same SQLite outbox as sales/outcomes, so it survives
  offline/power-loss and is delivered when connectivity returns.
- Surfaced in the admin Dashboard "Показанные рекомендации" section via
  `GET /api/admin/dashboard/recommendations`: shown/converted counts, conversion rate, "sold
  ≤ 2 min" count, avg/median time-to-sale, attributed revenue, a time-to-sale distribution, and a
  per-event log table (resolved pharmacy/pharmacist/trigger names).

## Reconciliation Sources

| Source                | How it enters                            |
| --------------------- | ---------------------------------------- |
| POS log sale          | `POST /api/posm/sales`                   |
| Standard-N Excel      | `POST /api/admin/reconcile/import-excel` |
| Mobile receipt photo  | `POST /api/mobile/receipts`              |
| Manual admin decision | Reconcile approve/reject                 |

Decision model:

- POS + Excel match -> auto approve;
- one source only -> manual moderation;
- duplicates/conflicts -> flagged/manual;
- no evidence after expiry -> expired.

## Screen/Broadcast

Screens are currently simplified:

- Admin uploads/replaces one broadcast media item.
- Backend creates/updates the active broadcast playlist.
- POSM polls active playlist.
- Connected cash desk count comes from heartbeat.

Legacy playlist CRUD remains in backend but admin UI focuses on broadcast.

## API

| Method | Path                                          | Purpose                                 |
| ------ | --------------------------------------------- | --------------------------------------- |
| POST   | `/api/posm/recommend`                         | Cart -> recommendation list.            |
| POST   | `/api/posm/recommendations/{eventId}/outcome` | Accepted/rejected outcome.              |
| POST   | `/api/posm/sales`                             | Printed sale source for reconciliation. |
| GET    | `/api/posm/playlists/active`                  | Active customer-display playlist.       |
| GET    | `/api/posm/app/version`                       | Current POSM app release.               |
| POST   | `/api/posm/heartbeat`                         | Online cash-desk presence.              |
| POST   | `/api/posm/cdp/lookup`                        | Customer lookup by phone.               |
| POST   | `/api/posm/cdp/register`                      | Customer profile registration.          |

## Windows Client Notes

See:

- `../App/scripts/README-distrib.md`;
- `../App/WINDOWS_RUNBOOK.md`;
- `../App/POSM_DEPLOY.md`.

WPF does not build on macOS. Use a Windows machine/VM with .NET 10 SDK for release packages.
