# Synthetic native WPF regression checks

Run on Windows with the .NET 10 Windows Desktop x64 runtime:

```powershell
dotnet run --project App.Ui.Tests/CustomerDisplay.Ui.Tests.csproj -c Release -- --output=C:\Temp\epharm-ui-proof
```

The default requires an interactive desktop and refuses session 0. Windows CI
can explicitly pass `--allow-noninteractive`; the JSON then records the actual
`interactiveSession` value. A noninteractive run proves WPF behavior, not a
visible window on a real cashier's desktop. The existing Windows/Standard-N
pilot is still required before enabling the production staff feature.

Fifty-four cases exercise the actual `StaffAuthWindow` and `RecommendationWindow`
assemblies through their own in-process controls. They include nonblocking close,
parent-accepted grants, rejected foreign/stale contexts, fail-open cash work, source
loss on an existing filled window, single-flight submits, local login password policy,
current-status RU/KK switching, server-provided retry deadlines and exhausted reset OTP.
Both login routes reject null, empty, unknown and registration-only successful-response
statuses as service failures, preserving the password and releasing the required
presentation. The same native case checks valid `pending` behavior and accepted-grant
closure remain unchanged; it uses fake HTTP and real parent policy/session guards.

Registration never offers SMS, including unknown/rolled-back capability. Both password
fields and identity remain intact after local errors, HTTP 400/401/403/409/429/503,
network errors, a real fifteen-second `HttpClient` timeout with fake transport,
malformed JSON and invalid successful-response payloads. Successful parent acceptance
closes the window and clears secrets; explicit navigation/dismissal clears them too.
Reset and activation still require OTP. Registration uses a four-character fixture
password. A timer check proves dispatcher liveness during the fake timeout, not a
cashier-desktop responsiveness or latency guarantee.

Activation is separately labelled in RU/KK; navigation to registration hides SMS
without making requests. After valid account creation, a rejected awaited parent
handoff offers manual login with IIN/password retained only in the open form,
confirmation cleared and the parent's bilingual diagnostic preserved. The test
proves no automatic replay, one explicit login (not re-registration), and secret
cleanup on close. Policy rollback follows the same recovery only for a validated
created-account response; malformed responses do not claim success.
Fresh-window checks additionally cover device/legacy context and RU/KK before any
navigation, after first render and after language changes: login has no SMS or
registration-only fields, and opening/switching language makes no auth requests.

Five recommendation cases cover empty/failed refresh, partial cart removal in the same
native window, stock uncertainty with unchanged financial quote, expiry semantics and
the actual `Displayed` event reading current rows. The pure reconciliation helpers and
real windows are tested, not `MainWindow`'s production HTTP/outbox integration.

A geometry case checks all six input hosts, compact layout and language switching.
A separate below-fold error case positions the submit button in the viewport, then
proves a new error scrolls itself into view without test-driven status scrolling,
focus theft, window activation or lost passwords. Countdown ticks do not trigger this
product error-reveal path.
A 90-second WPF watchdog closes test windows and returns exit code 3 on timeout.

Exit code 0 means all 54 cases passed; 1 is a test failure; 2 is a missing
interactive session or invalid arguments. Output defaults to `proof` beside the
executable and includes `qa-proof.json` and fourteen renders of its own WPF content.
Error screenshots scroll the form to the actual status; they do not imply all fields
fit at once. Use a fresh output directory to preserve before/after evidence.

The project references the actual App assembly. It never starts the POSM App or
MainWindow, never calls `EpharmConfig.Load()`, and never reads device credentials,
outbox, Standard-N or production configuration. A synthetic in-memory config and
fake `HttpMessageHandler` intercept every request; no sockets, DNS, real SMS,
account creation, Windows hooks or external UI automation are used. Only fixture
credentials enter the test process; proof contains boolean assertions, no
passwords, grants or request bodies. Generated output must remain untracked.

The nonblocking-login regressions verify that comparable Standard-N identity does
not enable fullscreen/topmost/forced activation, that a synthetic cashier textbox
retains keyboard focus, and that minimize/close/continue work after local validation,
HTTP 401/409 and during a pending registration. They preserve existing grant,
late-response, single-flight and password-retention checks.
