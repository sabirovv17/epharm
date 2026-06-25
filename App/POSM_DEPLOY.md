# POSM Deployment

Deployment guide for the C#/WPF cash-desk client.

## Build

Build on Windows with .NET 10 SDK.

```powershell
cd <repo>
dotnet publish App\CustomerDisplay.csproj -c Release -r win-x64 --self-contained `
  -p:Version=1.0.0 -o C:\Epharm\app
```

Auto-update works with a published app folder containing `CustomerDisplay.exe`, dependencies, LibVLC,
config, and scripts. Do not deploy `dotnet run` as production.

## Config

`C:\Epharm\posm.json` example:

```json
{
  "Enabled": true,
  "BackendBaseUrl": "https://epharm.78-140-246-238.sslip.io",
  "DeviceKey": "<POSM_DEVICE_KEY>",
  "PharmacistId": "u_smoke",
  "PharmacyId": "ph_smoke",
  "ScreenMode": "prod",
  "RecommendRefreshSec": 0,
  "PlaylistPollSec": 20,
  "MediaCacheDir": "C:\\Epharm\\media-cache",
  "UpdateEnabled": true,
  "UpdatePollSec": 1800,
  "HeartbeatPath": "C:\\Epharm\\heartbeat.txt",
  "HeartbeatSec": 15
}
```

Every key can be overridden by env variables. See `App/scripts/README-distrib.md`.

## Install Scheduled Tasks

Run as administrator:

```powershell
cd <repo>\App\scripts
powershell -ExecutionPolicy Bypass -File install-tasks.ps1 -InstallDir C:\Epharm\app
```

This creates:

- `EpharmPOSM` - starts on user logon and restarts on failure;
- `EpharmPOSM-Watchdog` - checks heartbeat and restarts hung/dead client.

For unattended startup after reboot, configure Windows autologin for the cash-desk user. Scheduled
task trigger is logon-based.

Uninstall:

```powershell
powershell -ExecutionPolicy Bypass -File uninstall-tasks.ps1
```

## Broadcast Video

Admin Screens section currently exposes a simplified broadcast flow:

1. Upload/replace the broadcast media.
2. Backend stores media in MinIO and updates the active broadcast playlist.
3. POSM polls `/api/posm/playlists/active`.
4. Customer display switches on the next poll.

## App Auto-Update

POSM periodically calls:

```text
GET /api/posm/app/version?platform=win-x64
```

Release flow:

1. Publish new version with bumped `-p:Version=...`.
2. Zip the published folder.
3. Upload zip to a URL reachable by cash desks.
4. Calculate SHA256.
5. Register via admin API `/api/admin/app-releases`.

The client downloads only HTTPS URLs and verifies SHA256 before applying.

## Recommendation Smoke

A real popup requires:

- POSM enabled;
- valid backend URL and device key;
- active campaign/rule for the scanned product;
- barcode in the cash-desk log or a name that matches uniquely.

Demo log line format:

```powershell
$log = "C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log"
$enc = [System.Text.Encoding]::GetEncoding(1251)
$line = "Add2Cheque iPartID=80309(4603423004936);sname=Аквалор;price=1620;quant=1"
[System.IO.File]::AppendAllText($log, "$line`r`n", $enc)
```

## Operational Rules

- `Q` must not be a casual production exit path; use protected exit/maintenance flow from current app.
- Watch the POSM log first when debugging.
- If one-monitor cash desk is in `prod` mode, customer display is suppressed; recommendation popup can still work.
- Rotate shared POSM device key to per-device keys before large rollout.
