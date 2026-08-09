# POSM Client

Path: `App/` and `Models/`.

Current client is C#/WPF/.NET 10. Older Electron references are historical and no longer describe the
implementation.

## Responsibilities

The POSM client runs on a Windows cash-desk machine:

1. Polls the authoritative active Standard-N Firebird receipt (`DOCS` + `DOC_DETAIL_ACTIVE`) for the
   local `zkassa` workstation session. It auto-reads server/path/login from the cashier `options.ini`.
2. Tails detailed cp1251 `zkassa.log` events as a compatibility fallback for older Standard-N builds.
3. Sends cart data to `POST /api/posm/recommend`.
4. Shows replacement/cross-sell recommendations to the pharmacist.
5. Sends accepted/rejected outcomes.
6. Reports printed sales to `POST /api/posm/sales`.
7. Mirrors receipt and broadcast media on the customer display.
8. Polls the effective active playlist and app version. The backend resolves the default or
   pharmacy-targeted profile, so changing assignments or videos does not require reinstalling POSM.
9. Sends heartbeat so admin can count online cash desks.
10. Stores outgoing non-real-time events in a local SQLite outbox and retries safely.
11. Sends any cashier id/name found in Standard-N as an audit signal; the backend decides the trusted
    internal pharmacist used for bonuses.

## Important Files

| Path                                  | Role                                               |
| ------------------------------------- | -------------------------------------------------- |
| `App/MainWindow.xaml[.cs]`            | WPF customer display and main integration shell.   |
| `App/MainWindow.StandardNReceipt.cs`  | Live Standard-N receipt reconciliation loop.       |
| `App/MainWindow.Recommendations.cs`   | Recommendation popup wiring.                       |
| `App/MainWindow.Screen.cs`            | Customer screen/video playlist logic.              |
| `App/MainWindow.Update.cs`            | App auto-update logic.                             |
| `App/RecommendationWindow.xaml[.cs]`  | Pharmacist recommendation popup.                   |
| `App/CdpForm.xaml[.cs]`               | POSM customer-phone/CDP form.                      |
| `App/Config/EpharmConfig.cs`          | Config/env parsing.                                |
| `App/Services/EpharmApiClient.cs`     | HTTP client with `X-Posm-Key`.                     |
| `App/Services/CheckoutSession.cs`     | Current receipt/cart lifecycle.                    |
| `App/Services/StandardNLogLocator.cs` | Bounded production cash-log discovery/cache.       |
| `App/Services/StandardNDbLookup.cs`   | Workstation-bound Firebird receipt/cashier reader. |
| `App/Services/SaleReporter.cs`        | Printed sale reporting.                            |
| `App/Services/OfflineOutbox.cs`       | SQLite outbox.                                     |
| `App/Services/OutboxFlusher.cs`       | Retry loop.                                        |
| `Models/Posm/*`                       | DTOs shared by POSM requests/responses.            |

## Matching Contract

The matching contract uses three ordered identities. Exact identifiers are preferred; a product name
is only a last-resort fallback.

POSM sends:

- `barcode` - authoritative exact EAN/GTIN match against the catalog/Medusa barcode;
- `sku` - Standard-N local `iPartID`, sent only when barcode is unavailable and matched against the
  campaign product's `ipartId`;
- `name` - normalized exact-name fallback;
- `qty`, price/total data for sales.

Backend resolves barcode, then `iPartID` when barcode is unavailable, then normalized name. Ambiguous catalog keys are skipped
instead of selecting an arbitrary product.

`ExtractBarcode` supports:

- explicit `barcode=...` or `ean=...`;
- values in `iPartID=<id>(<EAN>)` when the value is 8/12/13/14 digits and differs from the internal id.

If the log does not contain EAN, POSM performs a read-only lookup in local Standard-N. It first tries
`VW_WAREBASE_KASSA`, then `PARTS.ID/BARCODE/BARCODE1/ORIG_BCODE_IZG` with a release-stable fallback
when the optional manufacturer-barcode column is absent. A missing view or a
schema difference does not abort the fallback chain. A non-cancelled recommendation response belongs
to the current cart snapshot and is trusted; POSM does not repeat backend matching with incompatible
catalog product ids.

## Config

`posm.json` keys can be overridden by environment variables:

| Key                       | Env                                | Meaning                                                               |
| ------------------------- | ---------------------------------- | --------------------------------------------------------------------- |
| `Enabled`                 | `EPHARM_POSM_ENABLED`              | Enables backend integration.                                          |
| `BackendBaseUrl`          | `EPHARM_BACKEND_URL`               | Preferred backend origin, e.g. `https://epharm.inkar.kz`.             |
| `BackendFallbackBaseUrls` | `EPHARM_BACKEND_FALLBACK_URLS`     | Ordered backup origins; environment values use `;` or `,` separators. |
| `DeviceKey`               | `EPHARM_POSM_KEY`                  | POSM device key for `X-Posm-Key`.                                     |
| `PharmacyId`              | `EPHARM_PHARMACY_ID`               | Pharmacy/screen id.                                                   |
| `PharmacistId`            | `EPHARM_PHARMACIST_ID`             | Diagnostic fallback only; do not use a fixed person in production.    |
| `ScreenMode`              | `EPHARM_SCREEN_MODE`               | `dev` windowed or `prod` monitor behavior.                            |
| `VideoEnabled`            | `EPHARM_NO_VIDEO=true` disables    | Customer video playback.                                              |
| `PlaylistPollSec`         | `EPHARM_PLAYLIST_POLL_SEC`         | Playlist poll period.                                                 |
| `AppLogPath`              | `EPHARM_APP_LOG`                   | POSM app log path.                                                    |
| `StandardNLogPaths`       | `EPHARM_STANDARDN_LOG_PATHS`       | Optional explicit paths (`;`-separated in env).                       |
| `StandardNReceiptPollMs`  | `EPHARM_STANDARDN_RECEIPT_POLL_MS` | Active receipt poll interval; default 400ms.                          |

POSM v1.0.43 uses the workstation-bound active Firebird receipt as the primary live-cart source. It
still watches explicit and previously confirmed paths first, then the two legacy v1.0.23 paths:
`C:\Standart-N\Kassir\zkassa.log` and
`C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log`. In parallel it performs a bounded search near
running Standard-N/cashier processes and likely top-level installation folders. It searches only for
`zkassa.log`, avoids reparse points, never blocks the UI thread, and stores a path only after a real
cash-event marker is observed. The cache is `C:\Epharm\standardn-log-paths.txt`.

`Enabled` is effective only when key identity fields are present.

## Pharmacist Attribution

Pharmacist attribution comes exclusively from the active Standard-N user on the workstation:

1. POSM reads the active Standard-N id and full name and captures them once for the receipt.
2. Backend accepts an internal id only when it belongs to an active pharmacist assigned to the same
   pharmacy. It can also use an exact unique full-name match within that pharmacy.
3. Any other Standard-N id/name is marked `standardn_unmapped`, stored verbatim, and shown in the
   dashboard instead of being discarded.
4. Missing identity is marked `unresolved`. Unmapped/unresolved sales do not enter automatic bonus
   reconciliation until the employee identity is mapped.

For Auezova 134, the real cashier evidence confirmed `KASSA2`, remote Firebird server `MANAGER`, and
an `options.ini` that points to `C:\Standart-N\base\ztrade.fdb`. POSM selects only a current `zkassa`
session whose `WORKSTATIONS.COMPNAME` matches the local computer, so a shared database cannot silently
attribute another cash desk's receipt or pharmacist. Other Standard-N releases remain fail-safe: a
schema/connection failure preserves the last known UI state and the log compatibility path continues.

POSM sends API requests to `BackendBaseUrl` first. On public-gateway `404/502/503/504` or a connection
failure it retries the configured fallback origin. A non-final endpoint gets a two-second attempt
budget, so a hanging gateway cannot consume the whole recommendation timeout before `:8060` is tried.
The client probes the preferred origin again every five
minutes. Origins must not include `/login`; the client adds `/api/posm/*`. The temporary
`http://epharm.inkar.kz:8060` fallback is for POSM API traffic only and should be removed once the
HTTPS gateway works. Application-release downloads stay HTTPS-only.

## Screen Modes

- `dev`: windowed display for debugging.
- `prod`: with two monitors, customer display opens fullscreen on the second monitor and popup stays
  on the pharmacist/cashier screen; with one monitor, customer display is suppressed and recommendations
  can still work.

## Build

WPF builds only on Windows.

```powershell
cd <repo>\App
dotnet run

# release/self-contained
powershell -ExecutionPolicy Bypass -File scripts\publish-exe.ps1
```

The release package must include the exe, runtime dependencies, LibVLC files, `posm.json`, and `run.bat`.

## Deployment

For long-running cash desk installation:

- use `dotnet publish` or `publish-exe.ps1`, not `dotnet run`;
- install scheduled tasks with `App/scripts/install-tasks.ps1`;
- enable Windows autologin if the client must start after reboot without manual login;
- use the watchdog task and heartbeat file;
- publish app releases through `/api/admin/app-releases` for auto-update.

`setup-autostart.bat` copies the package to `C:\Epharm\app-<mode>\<version>`, compares key package
hashes before reusing an existing folder, performs a bounded handover from an old POSM process, and
reports success only after the expected executable path and a fresh UI heartbeat are verified.

POSM sends backend presence every 30 seconds. Backend considers a device online for 90 seconds and
persists last-seen/pharmacy mapping in Redis with an in-memory fail-safe. Presence is keyed by the
pair `pharmacyId + deviceId`, not by the Windows machine name alone: `KASSA1` can therefore exist
in multiple pharmacies without one live cash desk hiding another. The admin screen polls the
connected-device endpoint every 30 seconds.

## Operations

Useful docs:

- `App/scripts/README-distrib.md` - dev/release package operation.
- `App/POSM_DEPLOY.md` - production installation, scheduled tasks, update release flow.
- `App/WINDOWS_RUNBOOK.md` - Windows demo and barcode scan examples.

For Standard-N identity diagnostics, run `collect-posm-diagnostics.bat` as administrator on the
cash-desk machine and return the ZIP created on the desktop. The collector redacts device keys and
database passwords.
