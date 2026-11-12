# Private PR164 cross-process staff UI pilot

Supplementary synthetic Windows UI qualification. This folder is not product source,
an installer, a release archive or evidence that a pilot has passed.

Transfer **source-only.zip** (four source/build/readme files) or copy those files
explicitly. Do not transfer the local restore cache, `reference84`, `bin` or
`publish-layout84`. Local API/syntax compilation used a SHA-verified historical84
DLL only; it is not a version85 build or a Windows/VM run.

The host uses a direct assembly reference to the **official immutable product DLL**.
It does not reference or build the product project, invoke `CustomerDisplay.App`,
start the real POSM `MainWindow`, read `posm.json`, register accounts or send SMS.
All staff HTTP runs through a strict in-memory handler with synthetic data.

## Build on an isolated Windows runner

First download, hash/CRC-check and extract the exact official version85 bridge.
Use a fresh host publish directory separate from the artifact directory:

```powershell
dotnet publish .\Epharm.PrivateStaffPilot.csproj -c Release -r win-x64 --self-contained true `
  -p:AppArtifactRoot=C:\private-pilot\official85 `
  -p:PublishSingleFile=false -o C:\private-pilot\ui-host
```

No new package dependency or guest runtime installation is needed. Self-contained
publishing supplies the private host's Windows Desktop runtime; keep that closure
intact when transferring it to the VM. The product DLL is deliberately **not copied**
into host output. Transfer the independently verified official bridge separately.
Do not publish into a real POSM install or the artifact directory.

## Run on the disposable interactive VM only

Use an own `InteractiveToken` task in the signed-in desktop/session1. A session0
or noninteractive run is refused. Run the published EXE, not `dotnet host.dll`:

```powershell
& C:\epharm-pr164-pilot\ui-host\Epharm.PrivateStaffPilot.exe `
  --artifact-root C:\epharm-pr164-pilot\official85 `
  --output C:\epharm-pr164-pilot\evidence\crossprocess85 `
  --expected-version 1.0.85 `
  --expected-dll-sha256 '<64-character official CustomerDisplay.dll SHA256>' `
  --foreground-wait-seconds 30
```

The output directory must be new/empty, local, physical and outside both the
product artifact and host publish directory. Version/hash and the actually loaded
assembly path are checked. The host must have been compiled against that same
official version; an old reference or rebuilt product is not accepted.

The same **private host EXE** launches a separate process with `--cashier-helper`.
This process runs only a WinForms cashier/input window and verifies that it has
not loaded `CustomerDisplay`. This saves a duplicate runtime closure while keeping
distinct PIDs, process start times, HWNDs and OS keyboard/foreground state.

`ready.json` identifies the own synthetic cashier PID/start/session/HWND. It reports
`CASHIER_FOREGROUND_READY` when ordinary activation succeeded; do not click then.
Only if it reports `WAITING_FOR_CASHIER_FOREGROUND` and no final result exists,
one click on this identified synthetic cashier
window in the **own VM** is permitted. No foreground-lock timeout, security policy,
TZ, `AttachThreadInput` or input-injection bypass is used. Failure to observe the
real cashier HWND/input focus within the bounded wait is a failure, never a pass.

## Cases and proof

1. A known-source `StaffAuthWindow` (legacy `requireSignIn:true`) shows automatically.
   Real `GetForegroundWindow` PID/HWND and `GetGUIThreadInfo` input HWND remain in
   the independent cashier, immediately and over ten subsequent samples. Native
   caption/resize/minimize styles and non-topmost state are checked. Actual
   `WM_SYSCOMMAND/SC_MINIMIZE`, stable native minimized state and `WM_CLOSE` follow.
2. A login request is held in the in-memory handler. Actual `WM_CLOSE` destroys the
   window before the handler produces its otherwise valid success. No auth callback
   reaches the parent and no grant exists.
3. The same deferred flow invokes the actual product's enabled Continue button by
   its WPF UI Automation Invoke pattern. Closure/cancellation occurs before the late
   response, again without a grant or retry.

Screenshots contain only owned product visuals and the synthetic cashier's own
DrawToBitmap image, not the whole guest desktop. They contain synthetic identity
input only; no real keys, config, IINs, receipts, grants or HTTP bodies are emitted.

Accept only exit0 plus `cross-process-ui-proof.json` with `status=PASS`, exactly
three passing cases/all assertions true (12/14/13 assertions in the frozen source), unchanged official product file hashes,
correct expected/actual version+hash+loaded path, separate cashier PID and clean
owned-child exit0 without forced termination. Refuse if `watchdog-failure.json` exists. Exit1 is a failed case;
exit2 is a refused/preflight run. Preflight refusal may have no main proof file.

The child closes when the parent exits or after130seconds. An independent host
timer aborts after140seconds, records failure and terminates only its own process
and exact owned child. Keep a root-owned outer180second process/start/path watchdog
as a final bound for native-kernel/VM hangs. Parent/root may stop only these exact
owned pilot processes; do not restart UTM or control any other VM.

The exact host runtime/architecture are recorded in proof; do not describe a
self-contained host's runtime as the separately installed POSM runtime14.

This host proves neither physical Standard-N/scanner operation nor full POSM
startup, update/recovery, outbox preservation or two-screen video. Those are separate
exact-artifact acceptance gates and must remain separate in the final report.
