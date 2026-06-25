# Epharm POSM Distribution

This folder contains helper scripts for the Windows C#/WPF POSM client.

## Client Capabilities

- Reads Standard-N `zkassa.log`.
- Extracts barcode/name/qty/price.
- Calls backend recommendations.
- Shows pharmacist popup.
- Mirrors receipt and broadcast media on customer display.
- Sends sale reports through outbox.
- Sends heartbeat.
- Polls app version for auto-update.

## Run

From a packaged build:

```powershell
run.bat
```

or:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
.\run-kassa.ps1
```

The packaged launcher starts `CustomerDisplay.exe` in dev mode, so the customer screen opens as a
window on the left side of the primary monitor and POSM logs are printed in the same terminal.

The launcher does not set `EPHARM_LOG_PATH`. In the normal cash-desk flow the client listens to
Standard-N logs directly:

- `C:\Standart-N\Kassir\zkassa.log`
- `C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log`

When a cashier scans/adds a product in Standard-N, Standard-N writes `Add2Cheque` lines to
`zkassa.log`; the POSM client parses that file, sends the live cart to backend recommendations, shows
the pharmacist popup, and writes diagnostics to the app log. Delete/discount/service lines update the
local cart only and do not call the recommendation backend.

For a realistic test, scan/add products inside Standard-N. Do not use synthetic scan scripts unless
Standard-N is unavailable and you are doing parser diagnostics only.

From source:

```powershell
cd <repo>\App
$env:EPHARM_POSM_CONFIG = "C:\Epharm\posm.json"
dotnet run
```

## Config and Env

| Config | Env | Default / meaning |
| --- | --- | --- |
| `Enabled` | `EPHARM_POSM_ENABLED` | POSM backend integration. |
| `BackendBaseUrl` | `EPHARM_BACKEND_URL` | Backend host. |
| `DeviceKey` | `EPHARM_POSM_KEY` | `X-Posm-Key`. |
| `PharmacyId` | `EPHARM_PHARMACY_ID` | Pharmacy/screen id. |
| `PharmacistId` | `EPHARM_PHARMACIST_ID` | Pilot pharmacist id. |
| `ScreenMode` | `EPHARM_SCREEN_MODE` | `dev` or `prod`. |
| `VideoEnabled` | `EPHARM_NO_VIDEO=true` disables | Customer video. |
| `PlaylistPollSec` | `EPHARM_PLAYLIST_POLL_SEC` | Playlist polling interval. |
| `DebounceMs` | `EPHARM_RECOMMEND_DEBOUNCE_MS` | Scan debounce before `/api/posm/recommend`; default 150ms. |
| `RecommendRefreshSec` | `EPHARM_RECOMMEND_REFRESH_SEC` | Legacy; default 0. Recommendations are scan-triggered. |
| `MediaCacheDir` | `EPHARM_MEDIA_CACHE_DIR` | Local cache for admin-panel videos. |
| `AppLogPath` | `EPHARM_APP_LOG` | App log file path. |
| log path | `EPHARM_LOG_PATH` | Optional Standard-N log path override for non-standard cash desks. Do not set it for the default demo VM. |

## Screen Modes

- `dev`: windowed debugging mode.
- `prod`: fullscreen customer display on second monitor; if only one monitor exists, customer display is
  suppressed and only pharmacist-side behavior remains.

## Logs

```powershell
Get-Content "C:\Epharm\customerdisplay.log" -Wait -Tail 50
```

Backend POSM logs:

```bash
ssh root@<server> "cd /root/epharm && docker compose --env-file .env.prod -f docker-compose.prod.yml logs --tail=100 backend | grep POSM"
```

## Scripts

| Script | Purpose |
| --- | --- |
| `publish-exe.ps1` | Build self-contained win-x64 package/zip. |
| `run-kassa.ps1`, `run.bat` | Start packaged exe in dev mode with logs in the same terminal. |
| `install-tasks.ps1` | Install scheduled task + watchdog. |
| `uninstall-tasks.ps1` | Remove scheduled tasks. |
| `watchdog.ps1` | Heartbeat/process watchdog. |
| `standartn-discover.ps1` | Help discover Standard-N log paths. |
| `scan-into-standartn.ps1`, `epharm-scan.ps1` | Source-tree diagnostic helpers only when Standard-N is unavailable. They are not included in the handoff zip. Normal tests must scan inside Standard-N. |

## Debug Checklist

- Is `Enabled=true`?
- Are `DeviceKey`, `PharmacyId`, `PharmacistId` set?
- Does log show watched `zkassa.log` path?
- Does log show parsed `barcode`?
- Does backend return 401, timeout, empty recommendations, or actual recommendation?
- Is a matching active campaign/rule configured in admin?
