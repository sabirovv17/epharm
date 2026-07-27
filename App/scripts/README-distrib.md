# Epharm POSM Distribution

This folder contains helper scripts for the Windows C#/WPF POSM client.

## Client Capabilities

- Reads the local workstation's open Standard-N receipt from `DOCS` + `DOC_DETAIL_ACTIVE`, including
  `iPartID`, manufacturer barcode, name, exact quantity and receipt price.
- Auto-reads Firebird server/path/login from the running cashier's `options.ini`; when that file
  supplies the database path, its connection values outrank stale `EPHARM_STANDARDN_DB_*`
  environment variables left by older installations. Credentials are never written to logs or
  diagnostics.
- Uses `SESSIONS` + `WORKSTATIONS.COMPNAME` to bind receipt and cashier identity to this monoblock.
- Keeps configured/cached `zkassa.log` readers as compatibility fallback for detailed older builds.
- Calls backend recommendations.
- Shows pharmacist popup.
- Mirrors receipt and broadcast media on customer display.
- Sends sale reports through outbox.
- Sends heartbeat.
- Polls app version for auto-update.

## One-click Install

Copy the pharmacy-specific ZIP to the monoblock, extract it completely, and
run:

```text
setup-autostart.bat
```

The batch file requests administrator rights itself, installs the package's
`posm.json` as `C:\Epharm\posm.json`, and creates the direct autostart plus a
fully hidden one-minute watchdog. No terminal commands are required after it
reports success.

Setup always runs POSM from a versioned local folder. DEV uses
`C:\Epharm\app-dev\<version>`; PROD uses `C:\Epharm\app-prod\<version>`. The
source ZIP may be on a mapped drive or VM shared folder. Setup reuses a same-version folder only when
the executable, managed assembly, runtime metadata, installer, and watchdog hashes match. It then
closes only existing Epharm POSM processes with a five-second grace period, starts the exact selected
build, and verifies a fresh UI heartbeat.

Setup verifies the exact process path and a fresh UI heartbeat. Standard-N log
discovery continues in the running application without delaying installation.

Re-running the same setup repairs the tasks while preserving
`C:\Epharm\media-cache` and the offline outbox.

Windows automatic logon remains a workstation prerequisite for showing POSM
immediately after power restoration: Windows cannot display desktop software
until a user session exists.

The second monitor is optional. If Windows sees only one monitor, POSM stays
running without the customer video/receipt screen and continues to show
scan-triggered recommendation popups on the pharmacist's primary monitor.

## Identity Diagnostics

While the pharmacist is logged in to Standard-N, run `diagnose-standardn.bat`. It performs read-only
inspection of the local Firebird schema and opens:

```text
C:\Epharm\standardn-identity-diagnostics.txt
```

If wider support evidence is needed, run `collect-posm-diagnostics.bat`. It creates a redacted ZIP on
the Windows desktop with POSM logs, task state, relevant Standard-N paths and the focused identity
report. Neither script starts or ends a pharmacist shift and neither writes to the Standard-N DB.

If POSM still cannot confirm a cash log, run `capture-standardn-scan-source.bat`. It takes a local
snapshot, asks the operator to scan one real item, and creates a ZIP on the desktop identifying the
actual file/database state changed by that scan. It is read-only and does not alter the cheque.

For a complete real-workstation incident, use the standalone
`epharm-pharmacy-diagnostics.bat`. It has no companion files. The operator starts with an empty test
receipt, runs the batch as administrator, scans exactly one configured trigger product when prompted,
waits five seconds, and presses Enter. The collector creates one ZIP on the desktop with before/after
cash-log and POSM-log deltas, sanitized configuration, the exact running binary/task, backend/TLS
checks, window coordinates, display screenshots, and optional read-only Standard-N identity evidence.
It reports the first failed stage in the chain `Standard-N -> POSM parser -> receipt -> backend ->
popup`. Device keys, database passwords, access tokens, credentials, and URL signatures are redacted.

In the normal cash-desk flow the client watches explicit/cached paths plus the standard paths immediately:

- `C:\Standart-N\Kassir\zkassa.log`
- `C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log`

`standardNLogPaths` or `EPHARM_STANDARDN_LOG_PATHS` can specify non-standard paths. Without an explicit
path, bounded background discovery searches only near Standard-N/cashier processes and likely
installation roots. A confirmed path is cached at `C:\Epharm\standardn-log-paths.txt`.

When a cashier adds a product, the active Firebird receipt changes. POSM mirrors that state and calls
the recommendation backend only for a new/increased line. A decrease or deletion updates the customer
receipt and closes a stale popup without a new backend call. Detailed `Add2Cheque` lines, when present,
are processed as a deduplicated fallback.

For a realistic test, scan/add products inside Standard-N. Do not use synthetic scan scripts unless
Standard-N is unavailable and you are doing parser diagnostics only.

From source:

```powershell
cd <repo>\App
$env:EPHARM_POSM_CONFIG = "C:\Epharm\posm.json"
dotnet run
```

## Config and Env

| Config                    | Env                                | Default / meaning                                                                             |
| ------------------------- | ---------------------------------- | --------------------------------------------------------------------------------------------- |
| `Enabled`                 | `EPHARM_POSM_ENABLED`              | POSM backend integration.                                                                     |
| `BackendBaseUrl`          | `EPHARM_BACKEND_URL`               | Backend host.                                                                                 |
| `BackendFallbackBaseUrls` | `EPHARM_BACKEND_FALLBACK_URLS`     | Ordered temporary fallback origins; env values are separated by `;` or `,`.                   |
| `DeviceKey`               | `EPHARM_POSM_KEY`                  | `X-Posm-Key`.                                                                                 |
| `PharmacyId`              | `EPHARM_PHARMACY_ID`               | Pharmacy/screen id.                                                                           |
| `PharmacistId`            | `EPHARM_PHARMACIST_ID`             | Pilot pharmacist id.                                                                          |
| `ScreenMode`              | `EPHARM_SCREEN_MODE`               | `dev` or `prod`.                                                                              |
| `VideoEnabled`            | `EPHARM_NO_VIDEO=true` disables    | Customer video.                                                                               |
| `PlaylistPollSec`         | `EPHARM_PLAYLIST_POLL_SEC`         | Playlist polling interval.                                                                    |
| `DebounceMs`              | `EPHARM_RECOMMEND_DEBOUNCE_MS`     | Scan debounce before `/api/posm/recommend`; default 150ms.                                    |
| `RecommendRefreshSec`     | `EPHARM_RECOMMEND_REFRESH_SEC`     | Legacy; default 0. Recommendations are scan-triggered.                                        |
| `MediaCacheDir`           | `EPHARM_MEDIA_CACHE_DIR`           | Local cache for admin-panel videos.                                                           |
| `AppLogPath`              | `EPHARM_APP_LOG`                   | App log file path.                                                                            |
| `StandardNDbEnabled`      | `EPHARM_STANDARDN_DB_ENABLED`      | Read active pharmacist/prices from local Standard-N Firebird DB. Default `true`.              |
| `StandardNDbPath`         | `EPHARM_STANDARDN_DB_PATH`         | Optional path to `ztrade`; empty means POSM probes standard demo/prod paths.                  |
| `StandardNReceiptPollMs`  | `EPHARM_STANDARDN_RECEIPT_POLL_MS` | Local active-receipt polling, 400ms default (200-5000ms).                                     |
| `StandardNDbHost`         | `EPHARM_STANDARDN_DB_HOST`         | Explicit Firebird host; default is replaced by `[Connect] Server` from cashier `options.ini`. |
| `StandardNDbPort`         | `EPHARM_STANDARDN_DB_PORT`         | Firebird port. Default `3050`.                                                                |
| `StandardNLogPaths`       | `EPHARM_STANDARDN_LOG_PATHS`       | Optional explicit cash-log paths; env values are separated by `;`.                            |

If `ztrade` is not found or Firebird is unavailable, POSM remains running and keeps recommendations/video
working. In that case cashier identity and trusted prices are left empty in POSM logs/reports instead of
using stale fallback values. Raw Standard-N identity remains visible in analytics; automatic bonus
attribution requires a deterministic match to an active internal pharmacist of the same pharmacy.

POSM uses the primary `BackendBaseUrl` first. It retries a fallback after public-gateway `404/502/503/504`
or a connection error. Each non-final endpoint has a two-second attempt budget, so a hanging primary
cannot consume the complete recommendation timeout before fallback. POSM returns to testing the primary
every five minutes. Configure origins only, not
the admin page path (`/login`). Auto-update ZIPs remain HTTPS-only even while an HTTP fallback is active.

## Screen Modes

- `dev`: resizable `460x820` customer-screen preview at the top-left of the primary monitor.
- `prod`: fullscreen customer display on second monitor; if only one monitor exists, customer display is
  suppressed and only pharmacist-side behavior remains.

## Logs

```powershell
Get-Content "C:\Epharm\customerdisplay.log" -Wait -Tail 50
```

Backend POSM logs:

```bash
ssh adm-quasar@inkpim.inkar.kz "cd /home/adm-quasar/epharm && docker compose --env-file .env.prod -f docker-compose.prod.yml logs --tail=100 backend | grep POSM"
```

## Scripts

| Script                                       | Purpose                                                                                                                                                 |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `publish-exe.ps1`                            | Build self-contained win-x64 package/zip.                                                                                                               |
| `install-tasks.ps1`                          | Install scheduled task + watchdog.                                                                                                                      |
| `uninstall-tasks.ps1`                        | Remove scheduled tasks.                                                                                                                                 |
| `watchdog.ps1`                               | Heartbeat/process watchdog.                                                                                                                             |
| `standartn-discover.ps1`                     | Help discover Standard-N log paths.                                                                                                                     |
| `diagnose-standardn.bat`                     | Read-only Standard-N schema and active-user report.                                                                                                     |
| `collect-posm-diagnostics.bat`               | Create a redacted diagnostic ZIP on the Windows desktop.                                                                                                |
| `epharm-pharmacy-diagnostics.bat`            | Standalone controlled-scan collector for a complete pharmacy workstation incident; no companion `.ps1` is required.                                     |
| `scan-into-standartn.ps1`, `epharm-scan.ps1` | Source-tree diagnostic helpers only when Standard-N is unavailable. They are not included in the handoff zip. Normal tests must scan inside Standard-N. |

## Debug Checklist

- Is `Enabled=true`?
- Are `DeviceKey` and `PharmacyId` set?
- Does log show `БД Стандарт-Н: ... db=<path>` or a clear "not found" message?
- Does log show `POSM готов принимать сканы: открыт <actual zkassa.log path>`?
- Does `Скан товара` show the expected `PartId` and EAN? Missing EAN is enriched from `PARTS` when the
  log itself does not contain it.
- Does scan log show `pharmacistId` and non-zero price when `ztrade` is available?
- Does log show `POSM recommend request`, then `response`, `candidate`, and finally `POSM popup`?
- Does backend return 401, timeout, empty recommendations, or an actual recommendation?
- Is a matching active campaign/rule configured in admin?
