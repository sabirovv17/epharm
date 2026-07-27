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
  "BackendBaseUrl": "https://epharm.inkar.kz",
  "BackendFallbackBaseUrls": ["http://epharm.inkar.kz:8060"],
  "DeviceKey": "<POSM_DEVICE_KEY>",
  "PharmacistId": "u_smoke",
  "PharmacyId": "ph_smoke",
  "ScreenMode": "dev",
  "PlaylistPollSec": 20
}
```

The first URL is preferred. POSM automatically falls back to `http://epharm.inkar.kz:8060` only when
the HTTPS gateway does not respond correctly, then probes HTTPS again every five minutes. Keep this
value as an origin, without `/login`; POSM makes API calls under `/api/posm/*`. Remove the HTTP
fallback when the external HTTPS gateway is live.

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
$line = "Add2Cheque iPartID=80309(4603423004936);sname=Аквалор;price=1620;quant=1;kassir=Иванова"
[System.IO.File]::AppendAllText($log, "$line`r`n", $enc)
```

Barcode in parentheses is the important part. POSM also supports explicit
`barcode`/`barcode1`/`ean`/`ean13`/`bcode`/`штрихкод` fields.

POSM probes the local Standard-N Firebird database (`ztrade`) for the active cashier and sends any
id/name it can discover on `/recommend` + `/sales`. `ACTIVEUSERS` may be connection-local and empty,
so POSM also checks unfinished `SESSIONS`/`SP$SESSIONS` records and `HUMAN_ACTION_LOGS`. The log
token `kassir=<id>` / `cashier=<id>` is a fallback when the database does not expose the active user.
Backend preserves raw Standard-N identity and maps it only to an active pharmacist of the same pharmacy.

Prices are also enriched from `ztrade` by `iPartID` (`PRICES` / `VW_WAREBASE_KASSA`). If the cash
log has `price=0` or no price, POSM still logs and reports the real local retail price when the DB
is reachable. If the DB is not reachable, trusted price/pharmacist fields stay empty instead of using
stale fallback values.

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
- `POSM готов принимать сканы: открыт <actual zkassa.log path>`;
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
