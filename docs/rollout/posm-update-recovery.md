# POSM update recovery contract

This is a local candidate implementation, not evidence of deployment or fleet acceptance.

## Download and trust

The existing independent ECDSA P-256 manifest signature and SHA-256 checks remain mandatory.
No device key is sent to the artifact origin. The downloader now applies a 30-minute **whole
operation** budget, a 60-second body inactivity deadline, and at most four attempts. A stalled
`ResponseHeadersRead` body cannot permanently occupy the updater. Partial downloads survive;
resuming requires an exact `Content-Range` offset and final signed SHA-256 verification.

Package expansion rejects traversal, duplicate/case-colliding names, symlinks/reparse points and
non-runtime payloads. `posm.json`, credentials, scripts, queues, receipt directories and logs cannot
be supplied by an update archive. The supported bridge is exe/DLL/deps/runtimeconfig; a future
full-runtime package with additional file types needs explicit review of the allowlist.
Before shutdown the candidate managed assembly version must also match the signed Windows manifest;
a mislabeled archive cannot trigger an endless restart/reapply loop.

## Apply, readiness and rollback

1. While the old app still works, prepare every candidate file and SHA-256-verified previous binary
   under `<actual-install>/.epharm-update/<random-id>`. No live runtime file is modified yet.
2. Publish `active.json` only after all files and the recovery plan are complete. Failed preparation
   removes only its newly created, physically validated GUID directory.
3. Start the embedded PowerShell helper without changing a task, registry key or autostart target.
   The helper waits up to 60 seconds for the exact old PID/start time, then acquires the existing
   POSM single-instance mutex. The application guard tests **mutex ownership**, not object existence.
4. For each runtime file, copy to a same-directory temporary file, verify its hash, and atomically
   replace/rename it. The durable plan describes both old and new hashes before any replacement.
   This is a guarded multi-file transaction with **per-file atomicity**, not a filesystem-wide
   atomic directory switch. Unknown third-party file changes stop automatic recovery.
5. Release the instance mutex and start the candidate with a one-use random readiness token.
   The candidate must finish `MainWindow.OnLoaded`, have its local POSM/outbox/heartbeat initialized,
   and execute three background dispatcher ticks over at least 14 seconds. It writes an ACK tied to
   the token, PID and process start time. `Process.Start`, `Show` or an old heartbeat alone cannot pass.
6. If the exact candidate exits, cannot start, or fails the 90-second readiness deadline, stop only
   that candidate, reacquire the instance mutex, restore and verify all changed binaries, remove
   only newly introduced runtime files, and restart the previous version at its original path.
7. Persist the rejected package hash. The same broken archive is not retried at every poll; publishing
   a separately reviewed corrected package/hash is required. The old application keeps its configured
   task/watchdog paths and pharmacy configuration.

The readiness decision is latched once: an ACK arriving at or after the deadline cannot keep a
candidate running while the file transaction decides to roll back. Rollback requires both update
and application mutex ownership and a stopped child. If the child cannot be stopped or the mutex
cannot be reacquired, retain the active transaction as `recovery-required` and do not replace files.
Metadata failure after an accepted healthy startup likewise must not rewrite a live candidate.
PowerShell path checks reject dangling reparse points for JSON/temp/runtime paths. Failure logging
is best-effort, refuses linked paths and cannot mask the original recovery error.

A startup encountering an abandoned transaction invokes recovery before normal initialization.
Both preparation and recovery publish the embedded helper through a flushed temporary file and an
atomic rename; existing helper/temp reparse points are rejected before writing or replacing either.
If recovery cannot safely obtain the mutex, read the backup, or restore a locked/externally changed
binary, it retains the active marker and evidence instead of starting a mixed installation.
Inspect `.epharm-update/<id>/state.json` and `apply.log`; do not remove the marker to force startup.

Two newest completed transaction backups are retained before preparing the next candidate. Active,
incomplete and unknown bundles are never automatically pruned. New preparation refuses an 8 GiB
recovery-storage limit or less than 512 MiB remaining disk reserve. Extra/unrecognized files or
reparse points cause refusal rather than broad cleanup. This protects bounded storage; operational
disk monitoring and reviewed recovery maintenance are still necessary.

## What this health gate does not prove

- Backend availability, real Standard-N identity/receipt data, external SMS and a real customer
  display are separate acceptance checks. Offline network access must not force an otherwise
  functioning POSM to roll back.
- Updating **from an old installed updater** still executes that old updater for the first handover.
  A controlled initial installer/pilot is required before claiming this recovery protection fleet-wide.
- A simulated interrupted journal and locked-file test is not a physical power-loss/reboot test.
  In particular, runtime/host failure before the new managed bootstrap can run still needs the
  external installer/recovery procedure. Do not claim unconditional power-loss recovery.
- A signed but malicious release is outside the runtime-health gate: signing custody and exact-CI
  artifact review remain mandatory.

## Local automated evidence and release gates

`App.Tests/UpdatePackageDownloaderTests.cs` uses fake HTTP bodies to cover stall, slow trickle,
caller cancellation, resume and invalid ranges without network or credentials.

`App.Tests/UpdateTransactionTests.cs` executes the **production transaction functions** with Windows
PowerShell against disposable binary-shaped files. It covers copy interruption, a real NTFS sharing
violation, failed candidate launch, readiness timeout/token mismatch, interrupted journal recovery,
damaged backups, retention and preserved config/outbox bytes. Process-launch seams use synthetic
objects; they do not launch POSM, modify scheduled tasks or prove real WPF candidate startup.
Named-mutex tests additionally cover a retained but released helper handle and a held competing handle.
Deterministic deadline tests also cover a late ACK and refused mutex reacquisition; the latter
retains the pending marker without restoring over a candidate. Windows core tests and the separate
PowerShell 5.1 read-only collector contracts run in the required Windows CI job, not just Linux CI.
The embedded helper is published through a flushed temporary file and atomic move, never by
truncating an existing helper. Existing and dangling reparse entries are rejected. Archive/hash,
partial-body writes and final download publication also recheck physical paths; regressions cover
links present initially and introduced by the synthetic HTTP handler before open/publication.

Before any release, require the full core suite, native WPF suite and exact Windows publish build.
Then rehearse on a disposable Windows installation: previous → candidate, deliberately failed startup,
locked file, helper interruption, reboot/watchdog recovery, offline startup, two displays and return
to the previous version. Record exact artifact hashes, paths, startup/ACK times and preserved queue
and pharmacy-config hashes. Only an explicitly approved small device cohort may follow.

## Shown-event delivery during shutdown

`PendingShownPings` keeps dispatcher recording in memory, with one background writer journaling
before transfer to SQLite. Admission has an atomic 4,096-item memory bound. Disk recovery is streamed
independently and rotated so it cannot overflow that buffer or permanently starve behind a corrupt
record. An orderly exit gives journal-only draining up to two seconds and never waits indefinitely
for a blocked SQLite writer. A timeout is logged as **not guaranteed durable**; a power cut before
the first durable write, a full disk or a blocked writer cannot honestly be described as guaranteed
delivery. The tests cover ordinary immediate shutdown/restart and the bounded blocked-writer case.

## Read-only evidence for a workstation still on an older version

`tools/collect-posm-update-diagnostics.ps1` is a separate support collector, not an auto-remediation
or remote-command endpoint. It emits JSON to stdout, makes no network request, creates no files,
and changes no task, process, registry entry or configuration. Running it on a pharmacy workstation
still requires separately approved access; it has not been dispatched as part of this local fix.

From that workstation, an operator can run `powershell.exe -NoProfile -File
tools\collect-posm-update-diagnostics.ps1`. `-InstallRoot` accepts the verified installation path
if process discovery cannot read it; `-ConfigPath` accepts a separately verified custom config path.
The report contains bounded file metadata, executable versions, task status/paths, allowlisted
updater settings and fixed error-code counts from at most 512 KiB of each relevant log tail.
It never returns raw logs, command-line arguments, full configuration, device/signing keys,
readiness tokens, IINs or receipt rows. Reparse paths and network drives are refused.

Collector environment/TEMP are explicitly not the running POSM process's environment/TEMP.
Missing evidence is unknown, not proof that updates are disabled or a particular failure occurred.
Use a report collected at the affected workstation to distinguish disabled polling, old actual
process path, download/trust errors, disk/permission failures and interrupted application. Do not
restart Standard-N, disable signature verification, clear caches or change autostart based only on
an old heartbeat version. The synthetic file-only contracts are in
`tools/tests/test-posm-update-diagnostics.ps1`; they do not exercise workstation inventory.
Both scripts deliberately retain their UTF-8 BOM for Windows PowerShell 5.1. The contracts include
ASCII-only Unicode-escape fixtures written as explicit UTF-8 bytes, using actual old/current updater
messages; matching incorrectly decoded Russian strings in both scripts cannot make those tests pass.
