# POSM v1.0.44: Firebird authentication precedence

Date: 2026-07-22. Scope: Auezova 134 production package.

## Confirmed failure

The real-workstation diagnostic found the correct cashier log, workstation `KASSA2`, Firebird host
`MANAGER` and database `C:\Standart-N\base\ztrade.fdb`. Firebird then rejected POSM with:

```text
Your user name and password are not defined.
```

As a result, POSM could not read `DOCS`/`DOC_DETAIL_ACTIVE`; the customer receipt stayed empty and no
recommendation request was possible. This was upstream of campaign matching and popup rendering.

The machine also had stale POSM environment overrides from previous pilots: the application log was
redirected to the desktop even though the installed production config specified
`C:\Epharm\customerdisplay.log`. The same precedence rule could override Firebird credentials read
from the running cashier's `options.ini`.

## v1.0.44 behavior

1. When the database path is discovered from the running Standard-N `options.ini`, host, login and
   password from that same `[Connect]` section are the authoritative first connection candidate.
2. `posm.json`/environment connection values remain a fallback, but cannot silently replace the
   running cashier's credentials.
3. A successful candidate is cached for subsequent receipt polls. No password is written to logs.
4. Failed Firebird polling backs off to five seconds instead of retrying several times per second.
5. An explicit packaged `appLogPath` wins over stale `EPHARM_APP_LOG`, keeping the production log at
   `C:\Epharm\customerdisplay.log`.

## Auezova acceptance

Keep one trigger product in an open Standard-N receipt and verify:

1. `diagnose-standardn.bat` reports `Firebird connection: OK`.
2. `CURRENT CASH RECEIPT` contains the product line rather than `query failed`.
3. `C:\Epharm\customerdisplay.log` reports the connection source as
   `running Standard-N options.ini` and then `Скан товара ... активный чек БД Standard-N`.
4. The customer display mirrors the line and total.
5. The active admin rule produces the pharmacist popup.

Do not copy this package to other pharmacies until these checks pass on Auezova 134.
