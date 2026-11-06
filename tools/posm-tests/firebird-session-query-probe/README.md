# Isolated Firebird staff-session query probe

This console harness calls the production `StandardNDbLookup.ObserveStaffSession`.
It creates a fresh, generated `C:\Windows\Temp\Epharm-RpsSessionProbe-<GUID>.fdb`,
uses synthetic workstations/session rows only, and drops that same database after
disposing its connections. It never accepts a database path in its input and never
opens or writes the vendor pharmacy database. A cleanup failure fails the proof.

Build using the existing Core.Tests project and provider; no new package is added:

```sh
dotnet build tools/posm-tests/firebird-session-query-probe/FirebirdSessionQueryProbe.csproj
dotnet run --project tools/posm-tests/firebird-session-query-probe/FirebirdSessionQueryProbe.csproj -- --self-test
```

On the authorized Windows VM, provide an owner-private JSON file with exactly
`host` (`127.0.0.1` only), `port`, `user` and `password`. Do not pass credentials on
the command line or put that file in Git. The approved test account needs permission
to create and drop its own new database in Windows Temp.

```text
FirebirdSessionQueryProbe.exe --connection-file <private.json> --output C:\Windows\Temp\Epharm-RpsSessionProof-<new-name>.json
```

The output reports real expected/observed statuses, source version, and owned-database
cleanup. No connection string, password, government identifier or vendor row is printed.
Compilation and offline self-tests do not constitute Firebird execution evidence.

The matrix covers more than 100 expired, partial-host and noncashier records;
101 possibly current candidates; unknown/future/invalid clocks; exact domain/FQDN
aliases; native versus numeric/text/DATE/missing timestamp fields; and expiry clamps.
When every row is provably expired the narrowed SQL returns `no_session`.

Compatibility basis: Firebird's [REVERSE](https://firebirdsql.org/refdocs/langrefupd25-intfunc-reverse.html)
and [DATEADD](https://firebirdsql.org/refdocs/langrefupd25-intfunc-dateadd.html) are
available from 2.1. Expiry uses a timestamp cutoff, preserving subsecond precision;
numeric counters and text are never cast into timestamps.
