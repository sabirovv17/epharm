# Private PR164 qualification branch

Only `codex/pr164-qualification-20261112`, never merge this branch. The registered
`release-images.yml` is replaced **on this private branch only** with a manual QA
workflow. It cannot publish release images, tags, POSM files or `app_releases`.
All product files and mandatory `ci.yml` stay byte-identical to merged main
`addb2c97bf752038688e91a6902369da5194009b`.

## Root launch interface

After root review, commit/push this disposable QA branch, then:

```sh
gh workflow run release-images.yml --repo sabirovv17/epharm \
  --ref codex/pr164-qualification-20261112 \
  -f exact_commit=addb2c97bf752038688e91a6902369da5194009b \
  -f release_id=v0.2.16
```

This command is a handoff only; the preparer has not committed, pushed or run it.
The config starts with `mode.windows=true`, `mode.linux=false`. Windows requires
exact current main, its successful official Windows build job130969355166 in
PUSH CI43153930079, and exact official85/previous84 artifact identities. It may
prepare private Windows qualification while the rest of main CI is still running;
a failed/cancelled main is refused. Full successful current-main PUSH CI/P0 remains
mandatory before promotion or Linux acceptance.

## Windows job and early VM transfers

`pr164-qualify.py` guards repository, exact temporary ref, manual inputs, clean
checkout, allowed QA-only branch diff, current main and official CI/artifact APIs.
`pr164-prepare-windows.ps1` is the only new Windows build/download adapter.
It reuses the already allowlisted prepare/render/topology helpers, downloads and
checks official84 as well as85, and prepares these **private** Actions artifacts
before starting the real two-screen display check:

- `pr164-private-trusted-runtime85-<main>`: runtime85 closure, exact official85 and
  official84 five-file bridges, pinned video/driver archive, provenance and hashes.
  The runtime is built from exact main solely to obtain dependencies; all five
  application files are replaced with the immutable official85 bytes. The84 bridge
  is included for the separate root-owned previous-release baseline.
- `pr164-private-crossprocess-host85-<main>`: a self-contained supplemental host
  compiled against the actual official85 DLL, `Private=false`; no product DLL in
  its output. Follow `pr164-ui/README.md` on the owned interactive VM. CI compilation
  alone does not prove the three cross-process focus/cancellation cases.
- `pr164-NEVER-PUBLISH-recovery-fixtures-<main>`: healthy9.99.2 built from exact main
  with a CLI version override, plus a separate bad-start9.99.2 with deliberately
  invalid native EXE bytes. Both archives retain all five allowed bridge filenames,
  the9.99.2 managed DLL and exact hashes/provenance. These are fault fixtures,
  **never release candidates or publication inputs**.

After transfers, Windows must establish two actual extended desktop displays,
launch immutable85 to show its pharmacist popup and moving customer-display video,
then remove only its own display device and restore recorded firewall settings.
The existing driver's licence is retained unmodified. Proof explicitly distinguishes
virtual extended displays from physical pharmacy screens/scanner operation.

## Linux enablement later

Root fills `server.candidate.imagesRun` and `artifactId` with the official exact-main
v0.2.16 image build coordinates, then changes only `mode.linux` to true. The adapter
generates `QA_ARTIFACT_CONFIG` in runner temp; frozen `pr164-linux/*` files remain
unchanged. The Linux job rechecks the full source gate and executes the frozen
bounded15→16→15→16 load/status/data acceptance, with owner-only cleanup and aggregate
uploads. Its500 synthetic desks/JWT learners do not certify production capacity.
An old15 rollback explicitly reopens the previous blocked-JWT limitation; final16
must restore denial.

## Local validation and remaining gates

```sh
/private/tmp/epharm-pr160-actionlint/actionlint .github/workflows/release-images.yml
python3 -B .github/qa/pr164-qualify.py self-test
python3 -B -m unittest discover -s .github/qa/pr164-linux -p test_contracts.py -v
node --check .github/qa/pr164-linux/learner-jwt.mjs
```

Local checks do not execute Docker, Windows, VM, CI, production or application code.
macOS has no PowerShell executable; a real PowerShell AST parse is a mandatory
Windows step before preparation. Runtime gates remain: main CI/P0, official image
build and Linux acceptance, actual Windows two-screen output, owned-VM84→85 update
and fault recovery/lock/helper kill/real OS reboot/watchdog offline/manual return,
unchanged config/SQLite/outbox, cross-process UI cases, fresh backup/independent
restore/rollback bundle, deployment smoke and fleet observation. No local proof
marks those runtime gates passed.
