# POSM Client

Path: `App/` and `Models/`.

Current client is C#/WPF/.NET 10. Older Electron references are historical and no longer describe the
implementation.

## Responsibilities

The POSM client runs on a Windows cash-desk machine:

1. Reads Standard-N `zkassa.log` in cp1251 as a tailing log.
2. Parses receipt item lines, including barcode/EAN when present.
3. Sends cart data to `POST /api/posm/recommend`.
4. Shows replacement/cross-sell recommendations to the pharmacist.
5. Sends accepted/rejected outcomes.
6. Reports printed sales to `POST /api/posm/sales`.
7. Mirrors receipt and broadcast media on the customer display.
8. Polls active playlist and app version.
9. Sends heartbeat so admin can count online cash desks.
10. Stores outgoing non-real-time events in a local SQLite outbox and retries safely.

## Important Files

| Path | Role |
| --- | --- |
| `App/MainWindow.xaml[.cs]` | WPF customer display and main integration shell. |
| `App/MainWindow.Recommendations.cs` | Recommendation popup wiring. |
| `App/MainWindow.Screen.cs` | Customer screen/video playlist logic. |
| `App/MainWindow.Update.cs` | App auto-update logic. |
| `App/RecommendationWindow.xaml[.cs]` | Pharmacist recommendation popup. |
| `App/CdpForm.xaml[.cs]` | POSM customer-phone/CDP form. |
| `App/Config/EpharmConfig.cs` | Config/env parsing. |
| `App/Services/EpharmApiClient.cs` | HTTP client with `X-Posm-Key`. |
| `App/Services/CheckoutSession.cs` | Current receipt/cart lifecycle. |
| `App/Services/SaleReporter.cs` | Printed sale reporting. |
| `App/Services/OfflineOutbox.cs` | SQLite outbox. |
| `App/Services/OutboxFlusher.cs` | Retry loop. |
| `Models/Posm/*` | DTOs shared by POSM requests/responses. |

## Matching Contract

The current matching key is barcode/EAN from Medusa and the cash-desk log.

POSM sends:

- `barcode` - primary matching key;
- `name` - fallback;
- `sku` - Standard-N `iPartID`, diagnostic only;
- `qty`, price/total data for sales.

Backend resolves barcode first, then normalized name. Ambiguous matches are skipped.

`ExtractBarcode` supports:

- explicit `barcode=...` or `ean=...`;
- values in `iPartID=<id>(<EAN>)` when the value is 8/12/13/14 digits and differs from the internal id.

## Config

`posm.json` keys can be overridden by environment variables:

| Key | Env | Meaning |
| --- | --- | --- |
| `Enabled` | `EPHARM_POSM_ENABLED` | Enables backend integration. |
| `BackendBaseUrl` | `EPHARM_BACKEND_URL` | Backend host, e.g. `https://epharm.78-140-246-238.sslip.io`. |
| `DeviceKey` | `EPHARM_POSM_KEY` | POSM device key for `X-Posm-Key`. |
| `PharmacyId` | `EPHARM_PHARMACY_ID` | Pharmacy/screen id. |
| `PharmacistId` | `EPHARM_PHARMACIST_ID` | Pharmacist id credited in pilot config. |
| `ScreenMode` | `EPHARM_SCREEN_MODE` | `dev` windowed or `prod` monitor behavior. |
| `VideoEnabled` | `EPHARM_NO_VIDEO=true` disables | Customer video playback. |
| `PlaylistPollSec` | `EPHARM_PLAYLIST_POLL_SEC` | Playlist poll period. |
| `AppLogPath` | `EPHARM_APP_LOG` | POSM app log path. |
| log path | `EPHARM_LOG_PATH` | Explicit Standard-N log path. |

`Enabled` is effective only when key identity fields are present.

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

## Operations

Useful docs:

- `App/scripts/README-distrib.md` - dev/release package operation.
- `App/POSM_DEPLOY.md` - production installation, scheduled tasks, update release flow.
- `App/WINDOWS_RUNBOOK.md` - Windows demo and barcode scan examples.
