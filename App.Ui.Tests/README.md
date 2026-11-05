# Synthetic WPF staff-window regression checks

Run on Windows with the .NET 10 Windows Desktop x64 runtime:

```powershell
dotnet run --project App.Ui.Tests/CustomerDisplay.Ui.Tests.csproj -c Release -- --output=C:\Temp\epharm-ui-proof
```

The default requires an interactive desktop and refuses session 0. Windows CI
can explicitly pass `--allow-noninteractive`; the JSON then records the actual
`interactiveSession` value. A noninteractive run proves WPF behavior, not a
visible window on a real cashier's desktop. The existing Windows/Standard-N
pilot is still required before enabling the production staff feature.

Eight cases cover required window bounds, protected close and parent dismissal,
registration with a fresh matching grant and automatic closure, rejected foreign
context, service/SMS failure bypass, SMS-wait bypass and a closable unknown-session
form. A geometry case checks all six input hosts, compact layout and language switching.
Registration uses a four-character fixture password. Tests manipulate only their own in-process controls. A 30-second WPF
watchdog closes test windows and returns exit code 3 on timeout.

Exit code 0 means all eight cases passed; 1 is a test failure; 2 is a missing
interactive session or invalid arguments. Output defaults to `proof` beside the
executable and includes `qa-proof.json` and three renders of its own WPF content.

The project references the actual App assembly. It never starts the POSM App or
MainWindow, never calls `EpharmConfig.Load()`, and never reads device credentials,
outbox, Standard-N or production configuration. A synthetic in-memory config and
fake `HttpMessageHandler` intercept every request; no sockets, DNS, real SMS,
account creation, Windows hooks or external UI automation are used. Only fixture
credentials enter the test process; proof contains boolean assertions, no
passwords, grants or request bodies. Generated output must remain untracked.
