# Windows Runbook for POSM Demo

Use this when demonstrating or debugging the C#/WPF POSM client on Windows.

## Requirements

- Windows 10/11 x64.
- .NET 10 SDK for source builds.
- `App/` and `Models/` must be copied together because `CustomerDisplay.csproj` references shared DTOs.
- Optional: a demo MP4 for local video fallback.

## Run From Source

```powershell
cd C:\epharm\App
$env:EPHARM_POSM_CONFIG = "C:\Epharm\posm.json"
dotnet run
```

If RID is needed:

```powershell
dotnet run -r win-x64
```

## Config for Shared Backend

```json
{
  "Enabled": true,
  "BackendBaseUrl": "https://epharm.78-140-246-238.sslip.io",
  "DeviceKey": "<POSM_DEVICE_KEY>",
  "PharmacistId": "u_smoke",
  "PharmacyId": "ph_smoke",
  "ScreenMode": "dev",
  "PlaylistPollSec": 20
}
```

Use local backend with:

```json
{
  "Enabled": true,
  "BackendBaseUrl": "http://<host-ip>:8080",
  "DeviceKey": "dev-posm-key",
  "PharmacistId": "u_smoke",
  "PharmacyId": "ph_smoke",
  "ScreenMode": "dev"
}
```

## Demo Scan

Append a cp1251 Standard-N-like line while the app is running:

```powershell
$log = "C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log"
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
$enc = [System.Text.Encoding]::GetEncoding(1251)
$line = "Add2Cheque iPartID=80309(4603423004936);sname=Аквалор;price=1620;quant=1"
[System.IO.File]::AppendAllText($log, "$line`r`n", $enc)
```

Barcode in parentheses is the important part. POSM also supports explicit `barcode=`/`ean=` fields.

## Controls

The recommendation popup is informational: there is no accept/skip key. Fulfillment is
determined from the actual printed sale during reconciliation, not from a key press.

| Key   | Action                                                       |
| ----- | ------------------------------------------------------------ |
| `D`   | Demo recommendation popup if supported by the current build. |
| `Tab` | Switch Substitution / Cross-sell tabs (when both are shown). |
| `Q`   | Dev exit in demo builds.                                     |

## Logs

Default app log is on the desktop unless overridden by `EPHARM_APP_LOG`.

```powershell
Get-Content "$env:USERPROFILE\Desktop\customerdisplay.log" -Wait -Tail 80
```

Check for:

- config banner;
- watched `zkassa.log` paths;
- parsed lines;
- `recommend` request/result;
- playlist polling;
- heartbeat.

## Troubleshooting

| Symptom                    | Check                                                                           |
| -------------------------- | ------------------------------------------------------------------------------- |
| No popup                   | Log line read? Barcode present? Active campaign/rule exists? Backend reachable? |
| 401 from POSM API          | Wrong `DeviceKey`.                                                              |
| Empty customer screen      | No active broadcast playlist or POSM disabled.                                  |
| Video black in VM          | Disable video or use physical/GPU-backed Windows.                               |
| Broken Cyrillic item names | Log was not written as cp1251.                                                  |
| Window position wrong      | Check `ScreenMode` and monitor count.                                           |
