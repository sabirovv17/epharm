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
4. POSM displays recommendation on the pharmacist screen.
5. `F9` -> `/api/posm/recommendations/{eventId}/outcome` with accepted.
6. Backend creates `pending_bonus`.
7. POSM later sends printed sale to `/api/posm/sales`.
8. Reconcile matches sale/Excel/mobile evidence and credits bonus on approval.

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
