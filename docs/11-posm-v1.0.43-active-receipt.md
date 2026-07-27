# POSM v1.0.43: authoritative active receipt

Date: 2026-07-21. Scope: Auezova 134 production package.

## Confirmed failure

The pharmacy diagnostic proved that POSM, the second display, the UI heartbeat, video cache, backend
fallback and Windows tasks were running. The break occurred before the UI and network layers:

- `C:\STANDART-N\Kassir\zkassa.log` changed after a controlled scan;
- that production Standard-N build wrote only service messages such as `FillInfoBoard` and
  `ActivateLineByBaseID`;
- it did not write `Add2Cheque`, `iPartID`, barcode, product name or quantity;
- v1.0.42 therefore had no cart event to parse, so neither the customer receipt nor `/recommend` was
  updated.

The clean VM worked because its Standard-N build did emit the older detailed `Add2Cheque` format.
Adding more filesystem paths could not solve a payload that does not exist.

## v1.0.43 contract

The primary source is now Standard-N's own active Firebird receipt:

1. Read the running cashier's `[Connect]` values from `options.ini`. On Auezova 134 this resolves
   server `MANAGER` and database `C:\Standart-N\base\ztrade.fdb`; secrets remain in memory only.
2. Select an open `zkassa` row from `SESSIONS` whose `WORKSTATIONS.COMPNAME` matches the local Windows
   computer. Do not choose an arbitrary session from a shared database.
3. Select `DOCS.STATUS=0`, `DOC_TYPE=3`, `AUDIT_ID=<local session id>`.
4. Read its rows from `DOC_DETAIL_ACTIVE` and use `BCODE_IZG`/manufacturer barcode before internal
   cashier barcodes.
5. Reconcile the exact cart every 400 ms. New/increased quantity triggers one recommendation request;
   decrease/removal only updates local UI and closes stale recommendations.
6. Preserve detailed `zkassa.log` parsing as a deduplicated fallback for older releases.

The reader is read-only and fail-safe. A Firebird timeout/schema error does not touch Standard-N and
does not clear the last known customer screen. Errors are rate-limited in `C:\Epharm\customerdisplay.log`.

## Verification evidence

The query was exercised against a running Standard-N cash session and returned one exact receipt:

- document `23641`, session `1714`, workstation `WIN-3MKGOMEM8T5`;
- line `61032`, `PART_ID=79960`, quantity `1`, price `920`;
- manufacturer EAN `4600209003190`, not the internal receipt code `2412100000046`.

The C# release build succeeds with zero warnings/errors. The final pharmacy acceptance remains a real
cashier test because the production Auezova database is accessible only from that corporate network.

## Auezova acceptance

After extracting the v1.0.43 PROD ZIP, run only `setup-autostart.bat` as administrator. Then verify:

1. `C:\Epharm\customerdisplay.log` contains `Монитор активного чека Standard-N запущен` and identifies
   the Firebird server as `MANAGER`, not `localhost`.
2. Adding Ivatherm updates the customer receipt and produces `Скан товара ... активный чек БД`.
3. The active admin cross-sell shows Borjomi on the pharmacist screen.
4. Removing Ivatherm removes it from the customer receipt and closes the recommendation.
5. The log records the local Standard-N cashier id/name/session; the dashboard shows that seller once
   the existing mapping rules resolve the employee.
6. Admin Screens shows Auezova 134 online within 90 seconds.
7. Close POSM or reboot Windows: the scheduled task/watchdog restores it without a visible console.

Do not roll this build to the remaining pharmacies until these seven checks pass on Auezova 134.
