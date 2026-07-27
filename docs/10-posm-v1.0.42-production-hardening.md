# POSM v1.0.42: production workstation hardening

## Incident

The v1.0.41 package worked on a clean VM, while a pharmacy workstation could show none of the three
expected signals: receipt updates on the customer screen, scan-triggered recommendations, and the
Auezova 134 device in admin screen presence.

There were two independent production risks:

1. The installer reused a same-version local folder without comparing its content and deliberately
   left an already running POSM process untouched. A successful setup could therefore leave an older
   binary and its already loaded config running until a manual close or reboot. The watchdog matched
   only the process name, so it could also treat that old process as healthy.
2. v1.0.41 watched only two historic `zkassa.log` locations. A clean VM used one of them, but a real
   Standard-N installation can use a different drive/folder. Receipt mirroring and recommendations
   share this input, so both disappear when no cash event reaches POSM.

The public HTTPS gateway is a separate infrastructure issue: on 2026-07-21 it presented an expired
`*.inteq.kz` certificate and `https://epharm.inkar.kz/api/health` returned `404`, while
`http://epharm.inkar.kz:8060/api/health` returned `200`. POSM retains the `:8060` API fallback, but a
pharmacy network must permit that outbound port until IT fixes routing on 443.

## v1.0.42 behavior

- setup compares key SHA-256 hashes before reusing a local same-version folder;
- setup performs a bounded handover, starts the exact expected executable, and requires a fresh UI
  heartbeat before reporting success;
- watchdog matches the configured executable by full path, not process name alone;
- POSM tries explicit, cached, and legacy Standard-N log paths immediately;
- bounded background discovery searches for `zkassa.log` near cashier processes and likely install
  roots without blocking the UI or scanning arbitrary files across the full workstation;
- a path is cached only after a real Standard-N cash marker is observed;
- backend heartbeat runs every 30 seconds and logs the active backend origin or an offline state;
- backend stores device last-seen/pharmacy mapping in Redis and keeps an in-memory fail-safe.

The Redis backend hardening passed local unit/integration tests but was not deployed on 2026-07-21:
SSH to `10.10.1.76:22` timed out from the current network. The currently deployed in-memory presence
implementation is sufficient for live heartbeats and already lists `KASSA2`; Redis deployment remains
an operations follow-up once internal SSH/VPN access is restored. The v1.0.42 workstation fix does not
depend on that deployment.

## Live backend proof before workstation rollout

The trusted `:8060` API was checked with the Auezova package key on 2026-07-21:

- heartbeat returned HTTP 200;
- `/api/admin/screens/connected` listed real device `KASSA2` under
  `sloc_01KSAHYDSE5MVP79KGJSH2F2SK` / Auezova 134;
- an exact Ivatherm barcode request returned the active Borjomi cross-sell with no conflict.

Therefore the current server, pharmacy mapping, device key, and campaign are valid through `:8060`.
Missing receipt/recommendation UI on the real workstation is on the local process/log-input path, not
in the configured campaign. v1.0.42 addresses that local path and makes backend delivery explicit in
the application log.

## Acceptance criteria on Auezova 134

1. Run `setup-autostart.bat` once as administrator; it must finish with the expected v1.0.42 path and
   fresh heartbeat confirmation.
2. `C:\Epharm\customerdisplay.log` must contain `Heartbeat backend подтверждён` and the Auezova
   pharmacy id `sloc_01KSAHYDSE5MVP79KGJSH2F2SK`.
3. Within 30 seconds, admin `Управление экранами` must list Auezova 134.
4. The log must contain `POSM готов принимать сканы: открыт <path>` after Standard-N is running.
5. Adding a configured trigger item must update the customer receipt and show a pharmacist popup.
6. Removing the trigger item must remove the local recommendation state.
7. Closing the setup console must not stop POSM; after reboot and Windows user logon, scheduled task
   and watchdog must restore it without a visible command window.

If criterion 2 fails, the current external gateway/port policy must be fixed. If criterion 4 fails,
run the bundled read-only diagnostic collector and use its discovered exact path in
`standardNLogPaths`; normal operation does not require diagnostics or terminal commands.

For the unresolved Auezova 134 workstation discrepancy, the canonical evidence collector is
`App/scripts/epharm-pharmacy-diagnostics.bat`. It is a single self-contained file and performs one
controlled scan without restarting or modifying either application. Its desktop ZIP records the exact
`zkassa.log` append, POSM consumption/parser/receipt/backend/popup chain, scheduled task and executable
paths, top-level window coordinates, both Windows displays, backend/TLS behavior, and sanitized
Standard-N identity evidence. This report must be collected before changing the v1.0.42 package again;
the first failed stage determines the next code or configuration change.
