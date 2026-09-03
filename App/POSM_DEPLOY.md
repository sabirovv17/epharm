# POSM Deployment

Deployment guide for the C#/WPF cash-desk client.

## Build

Build on Windows with .NET 10 SDK.

```powershell
cd <repo>
dotnet publish App\CustomerDisplay.csproj -c Release -r win-x64 --self-contained `
  -p:Version=1.0.47 -o C:\Epharm\app
```

Auto-update works with a published app folder containing `CustomerDisplay.exe`, dependencies, LibVLC,
config, and scripts. Do not deploy `dotnet run` as production.

## Config

`C:\Epharm\posm.json` example:

```json
{
  "Enabled": true,
  "BackendBaseUrl": "https://epharm.inkar.kz",
  "BackendFallbackBaseUrls": ["http://epharm.inkar.kz:8060"],
  "DeviceKey": "<POSM_DEVICE_KEY>",
  "PharmacistId": "",
  "PharmacyId": "ph_smoke",
  "ScreenMode": "prod",
  "RecommendRefreshSec": 0,
  "PlaylistPollSec": 20,
  "MediaCacheDir": "C:\\Epharm\\media-cache",
  "UpdateEnabled": true,
  "UpdatePollSec": 300,
  "HeartbeatPath": "C:\\Epharm\\heartbeat.txt",
  "HeartbeatSec": 15,
  "ReceiptCaptureEnabled": true,
  "ReceiptCaptureDir": "C:\\Epharm\\receipts",
  "FiscalReceiptInboxDir": "C:\\Epharm\\fiscal-inbox",
  "FiscalReceiptTrustedSources": ["standardn-kkm-sdk", "ofd-api"],
  "FiscalReceiptPollSec": 2,
  "FiscalReceiptMaxClockSkewSec": 900,
  "FiscalReceiptMaxArtifactMb": 10,
  "ReceiptCaptureActiveRetentionDays": 2,
  "FiscalReceiptCompletedRetentionHours": 24,
  "StandardNDbEnabled": true,
  "StandardNDbPath": "",
  "StandardNReceiptPollMs": 400
}
```

Every key can be overridden by env variables. See `App/scripts/README-distrib.md`.

`BackendBaseUrl` is the preferred public HTTPS origin. `BackendFallbackBaseUrls` is an ordered list
of temporary alternatives: the client switches to it after a gateway/network failure and retries the
HTTPS origin every five minutes. Specify an origin only, never `/login`: POSM appends `/api/posm/*`
itself. The `:8060` fallback is plain HTTP and must be removed after the HTTPS gateway is available.
It is not used for automatic application updates: release ZIP downloads remain HTTPS-only.

## One-Click Pharmacy Install

For a pharmacy monoblock:

1. Copy the pharmacy-specific ZIP to any local folder.
2. Extract the entire ZIP.
3. Run `setup-autostart.bat` and accept the administrator/UAC prompt.

The installer validates `posm.json`, stores the active pharmacy config at
`C:\Epharm\posm.json`, and creates the tasks below:

- `EpharmPOSM` - starts on user logon and restarts on failure;
- `EpharmPOSM-Watchdog` - checks heartbeat and restarts hung/dead client.

Setup copies the package from any accessible source into a versioned local
folder: `C:\Epharm\app-dev\<version>` for DEV or
`C:\Epharm\app-prod\<version>` for PROD. A repeated setup reuses a local copy only when key SHA-256
hashes match the extracted package. Setup then performs a bounded handover from existing Epharm POSM
processes and verifies the exact new executable path plus a fresh UI heartbeat. Standard-N log
discovery continues in the running client without delaying installation.

Re-running `setup-autostart.bat` repairs the tasks without deleting the media
cache or offline outbox.

The second monitor is optional. With two monitors, POSM shows the fullscreen
customer video/receipt screen on monitor 2 and recommendation popups on the
pharmacist monitor. With one monitor, the customer screen stays hidden while
POSM continues listening to Standard-N and shows scan-triggered recommendation
popups on the primary pharmacist monitor.

For unattended visible startup after power loss, Windows must automatically
log in to the cash-desk user. A desktop application cannot display a window
before Windows creates an interactive user session.

Installation diagnostics are written to `C:\Epharm\install.log`; the last
machine-readable result is `C:\Epharm\install-status.json`.

Uninstall:

```powershell
powershell -ExecutionPolicy Bypass -File uninstall-tasks.ps1
```

## Broadcast Video

Admin Screens section exposes a 12-slot broadcast flow:

1. Upload or replace any slot from 1 to 12 independently.
2. Backend stores media in MinIO and returns filled slots as one ordered active playlist.
3. POSM polls `/api/posm/playlists/active`, downloads changed videos to the local cache, and rotates
   them in slot order.
4. Empty slots are skipped. A network failure does not stop the currently cached playlist.

## App Auto-Update

POSM periodically calls:

```text
GET /api/posm/app/version?platform=win-x64
```

Release flow:

1. Publish new version with bumped `-p:Version=...`.
2. Zip the published folder without `posm.json`; pharmacy configuration must remain local.
3. Upload zip to a URL reachable by cash desks.
4. Calculate SHA256.
5. Register via admin API `/api/admin/app-releases`.

The client downloads only HTTPS URLs and verifies SHA256 before applying.
The download endpoint also requires the client's `X-Posm-Key` header.

## Internet Order Fulfillment

This feature is independent from recommendations, sales capture and the customer display. It is
disabled unless `fulfillmentEnabled=true` is present in the pharmacy's local `posm.json` and the
backend feature flag is enabled.

On first use POSM exchanges the fleet bootstrap key for a random per-device token and protects that
token with Windows DPAPI for the current cash-desk user. Backend enrollment is closed by default:
operations may set `FULFILLMENT_DEVICE_REGISTRATION_ENABLED=true` only for a controlled registration
window, verify the expected `(pharmacyId, deviceId)` rows, and immediately set it back to `false`.
Closing enrollment does not revoke existing tokens. Tokens can be revoked individually in HQ.

The local queue is paginated and cached atomically. Another till can complete an order without
leaving a stale open card: POSM resolves the terminal state of any card no longer returned by the
active queue. A network failure leaves the last known queue visible as offline and never enables an
action that has not been confirmed by backend optimistic locking.

Production rollout and acceptance criteria are in `docs/19-order-fulfillment.md`.

## Exact Fiscal Receipt Capture

The current pipeline does not intercept, replace or delay the Standard-N fiscal print job:

1. While the cart is open, POSM atomically updates
   `C:\Epharm\receipts\active\<saleId>\sale.json`.
2. A confirmed close/print signal creates `pending\<saleId>\sale.json`; no image is reconstructed.
3. An approved read-only KKM/OFD adapter publishes the original PDF/PNG plus a manifest into
   `C:\Epharm\fiscal-inbox` after fiscalization.
4. POSM correlates and validates the source, then copies the file byte-for-byte as
   `pending\<saleId>\fiscal-receipt.pdf` or `.png` and records its SHA-256.
   The one-time inbox handoff is removed only after the durable copy passes a second hash check.
5. The structured sale and later fiscal enrichment use separate durable outbox records. Backend
   ignores legacy `artifactFormat=png` claims without a hash and rejects conflicting evidence.
6. POSM deletes its own exact copy only after the dedicated fiscal-metadata ACK and retention period.

Unfinished drafts older than the configured retention are removed. Damaged artifacts are moved to
`C:\Epharm\receipts\quarantine`. Set `EPHARM_RECEIPT_CAPTURE_ENABLED=false` to disable this isolated
capture path; structured sale reporting continues.

The adapter is hardware-specific and must be accepted on one real cash desk before a fleet release.
The current Auezova evidence shows direct `TFR_Shtrih.PrintCheque`, so Windows Spooler and the active
Firebird cart cannot be treated as the fiscal original. The handoff directory must allow writes only
from the approved adapter service and reads from POSM. Full contract and pilot matrix:
`docs/17-posm-exact-fiscal-receipt.md`.

## Recommendation Smoke

A real popup requires:

- POSM enabled;
- valid backend URL and device key;
- active campaign/rule for the scanned product;
- matching barcode first, then `iPartID` when EAN is unavailable, then a uniquely normalized name;
- local Standard-N Firebird DB (`ztrade`) reachable when the cash log does not contain the barcode.

An identified cashier is required for seller attribution and bonuses, but never for showing the
recommendation itself.

Demo log line format:

```powershell
$log = "C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log"
$enc = [System.Text.Encoding]::GetEncoding(1251)
$line = "Add2Cheque iPartID=80309(4603423004936);sname=Аквалор;price=1620;quant=1"
[System.IO.File]::AppendAllText($log, "$line`r`n", $enc)
```

Where the installed Standard-N schema is recognized, POSM enriches the scan with:

- an active cashier id/name from the discovered user/session tables;
- barcode/name/retail price from `VW_WAREBASE_KASSA`, then `PARTS`, with `PRICES` as a price-only
  fallback by `iPartID`.

POSM v1.0.43 primarily reconciles the local workstation's open Standard-N receipt from
`DOCS`/`DOC_DETAIL_ACTIVE`; `options.ini` supplies the actual Firebird server, path and credentials.
It also watches configured/cached paths and the two v1.0.23 paths. In parallel, a
bounded background locator searches for `zkassa.log` near running Standard-N/cashier processes and
likely installation roots. A path that emits a real cash event is cached in
`C:\Epharm\standardn-log-paths.txt`. Files are reopened after truncation or rotation.

The backend presence heartbeat is sent every 30 seconds. A device expires after 90 seconds without a
pulse; Redis stores the device/pharmacy last-seen state so a backend restart or a second backend
instance does not temporarily erase the online-screen list. Starting with v1.0.46, every pulse also
contains the current Windows monitor count and POSM application version. The admin Excel inventory therefore resolves the client
screen as `Есть` for two or more monitors, `Нет` for one, and `Не определено` for clients that have
not completed the rolling auto-update yet. No pharmacy-specific `posm.json` is replaced by updates.

The v1.0.46 rollout uses a compact bridge ZIP containing only the four application host files. It is
compatible with the installed v1.0.30-v1.0.45 self-contained runtime and avoids downloading the
roughly 160 MB full package to every pharmacy. Once the bridge is installed, update downloads resume
from a persistent `.part` file after a network interruption, retry four times, verify mandatory
SHA-256, and poll for the next release at least every five minutes. Update apply diagnostics are kept
in `C:\Epharm\update.log`.

Bootstrap releases must be downloadable from the same pharmacy networks that call the version API.
An old client can reach the API through the HTTP `:8060` fallback while still being unable to fetch
an `https://epharm.inkar.kz/...` package URL. For v1.0.46 the compact, credential-free bridge is also
published as a GitHub Release asset and that HTTPS asset is used as the current release URL. Keep the
identical ZIP on the application server as an operational mirror, verify both copies against the
registered SHA-256, and never include `posm.json`, a device key, or pharmacy-specific data in a public
release asset. A successful rollout is confirmed by non-legacy update checks plus `appVersion` and
`monitorCount` values in subsequent heartbeats; a version-check log alone does not prove installation.

If `ztrade` is not found, POSM does not fail: recommendations/video continue, while trusted
`pharmacistId` and price fields remain empty until the DB path/Firebird connection is available.
Do not assume `ACTIVEUSERS` contains rows on every Standard-N release. POSM also probes unfinished
cash-desk sessions in `SESSIONS`/`SP$SESSIONS` and resolves names through `HUMAN_ACTION_LOGS`. Run
`collect-posm-diagnostics.bat` as administrator on the real machine and inspect its redacted archive
before adapting queries. Run `diagnose-standardn.bat` after installation for a focused read-only
identity report. Raw id/name remains visible in the dashboard even before internal mapping.

## Operational Rules

- `Q` must not be a casual production exit path; use protected exit/maintenance flow from current app.
- Watch the POSM log first when debugging.
- If one-monitor cash desk is in `prod` mode, customer display is suppressed; recommendation popup can still work.
- Rotate shared POSM device key to per-device keys before large rollout.
