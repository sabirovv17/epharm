# Operational acceptance: 30 October 2026

This is the original read-only observation and preparation record. Subsequent backup-only
production work and actual recovery are recorded in `actual-offsite-and-scheduled-restore.json`;
newer source/candidate preparation is in `fresh-release-preparation-089.json`. The application
cutover has not occurred. In particular, the original observations below must not be read as a
claim that no later production backup changes were made or that all release gates are closed.

Production was observed read-only through existing authorised SSH access. No deploy, restart,
backup/retention execution, schema/data change, cleanup or flag change was performed. The associated
`ops-readonly-acceptance.json` contains sanitized aggregate observations; credentials and person-level
data are excluded. Observations are timestamped, not a release approval.

## Confirmed current state

- Public health is 200 with verified TLS for ePharm and CRM. Corporate DNS resolves directly to
  `.76` and `.80`. A separate `curl --resolve` probe through `.74` confirms both health routes return
  200 with successful TLS verification. This still does not prove connectivity from external pharmacies.
- `.74` nginx maps ePharm to `.76:8060`, CRM to `.80:80`. The public ePharm origin stays unchanged.
- Running ePharm is backend `v0.1.47` / `aa37b1e720923334cef8265f66a507d549b32780`, frontend
  `v0.1.53` / `a3feef372acf6509c8c088fea4265b8aaae57581`. Capture this exact current pair again
  before a switch; an older `v0.1.52` rollback plan is stale.
- `.76` PG16.14 is at schema065, approximately 2.23 GB, 73 public tables. Read-only counts are
  1,820,394 POSM sales, 47,010 recommendation events, no pending bonuses or wallet entries.
- `.80` CRM PG18.6 has 57 public tables, 680 branches, 82 tasks, 27 dispatches, 3 plans, no legacy
  visit reports or reward outbox entries. Runtime source matches CRM02501dcc; only two test scripts
  are absent on the live host. Git dirty state is not evidence of missing runtime source.
- `.76` backup and restore units last succeeded on 29 October 11:44/11:46 UTC. Read-only restic
  metadata confirms a 29 October 11:41 UTC encrypted SFTP snapshot. This is the older PG/MinIO
  policy, not proof of recovery for newly mounted native files and D9 keys.
- Current free root space is about 42.08 GB on `.76`, 19.70 GB on `.80`. Effective daily backup
  root holds about 3.35 GB, protected release bundles 3.18 GB, POSM downloads 1.02 GB.
- Current main is `a3feef372acf6509c8c088fea4265b8aaae57581`, its push CI and P0 passed. Its separate
  private Sentry source-map upload failed. The unification/D9 candidate is not yet this main.

## Gates which remain operationally unproven

Subsequent fresh source captures and complete private file/object copies are recorded separately in
`ops-fresh-source-preservation.json`. They include 27,152 additional POSM sales versus the earlier
rehearsal snapshot, all 31 current CRM storage files, 20 POSM download files, and 9,485 current MinIO
objects. The latter occupy **two buckets**, `epharm-receipts` and `pim-product-images`; a receipts-only
backup cannot satisfy full preservation. Adding both buckets to the protected backup environment
and verifying their actual offsite recovery is an explicit pre-cutover gate, not a production change
made by this audit.

1. **Provenance:** reviewed candidate merged to current main, then exact-main latest push CI/P0
   and matching immutable images. Source-map failure remains a separate observability issue.
2. **G4:** actual Windows/Standard-N scan, customer screen, recommendation popup/ACK/outcome,
   QR and installed updater continuity. Protocol, browser and synthetic tests do not establish this.
3. **G5 / D9:** recover matching DB, MinIO, private files and separate key artifact from offsite into
   an isolated target; verify hashes and synthetic vault decryption. Verify old-code compatibility
   and the policy after new financial writes. Copying an old DB back is not an acceptable rollback.
4. **G11:** capture current source changes and files, preserve original IDs/history/old links,
   resolve required explicit owner/PERSON bindings, and prove one operational writer. CRM source
   starts weekly generation and reservation expiry from `scripts/api_server.py:5436,5460,7492`;
   absence of separate systemd scheduler timers does not mean these writers are stopped.
5. **G13:** measure fresh data, indexes, WAL/temp, private files, downloads, backups and image
   archives; show peak capacity and a growth horizon on both hosts. Free space alone is insufficient.

The user has confirmed the three «Заказчик 4596» placement plans are historical tests. Preserve them
as read-only source history without inventing a company, producer binding or PERSON. Existing
unresolved owner diagnostics remain visible. NULL company/producer does not itself prevent future
weekly generation: native automation/financial activation stays OFF and needs a separate gate for
these test plans and legacy writers. The one existing closed source item is **cancelled**, not an
approved completed report. No replacement completed task was created. A backup on `.80` shares
site risk with `.76`.

## Safe sequence for the integration owner

These are future rehearsal/cutover steps, not commands executed by this audit.

1. Pin the reviewed main and require the source gate:
   `python3 tools/release/verify-release-source.py <exact-current-main-40hex>`.
   Capture current image IDs and protected config/Compose hashes again without printing secrets.
2. Configure the explicit native-file/key backup roots and mandatory flag in the protected backup
   environment; verify the same physical roots as Compose. Retain an independent recovery secret
   outside both servers. Native backup tools and their deployment are a separate reviewed artifact.
3. After operator approval, create fresh full-chain backups (initially local retention dry-run),
   recover that specific offsite snapshot to isolated storage and run the full restore test plus D9
   decryption and source-file manifest checks. Nothing here changes live business roots or keys.
4. Capture a fresh consistent `.76` DB and `.80` source DB/files with the latest writes. Repeat the
   existing pinned candidate/materialization rehearsal against those fresh artifacts, preserve all
   source tables/IDs/files and verify deltas. Never restore the 29 October rehearsal DB over live sales.
5. Rehearse a bounded writer transition: legacy task/hold/reward writers cease before the native
   equivalents start; legacy links/callbacks/headers/media access remain supported by the reviewed
   compatibility routes. Keep feature flags unchanged until each corresponding acceptance succeeds.
6. Create a fresh exact current image/config rollback bundle and rehearse
   `tools/release/deploy-archive-two-image.sh <backend-tag> <frontend-tag> <merged-commit> <backend-image-id> <frontend-image-id> <running-backend-tag> <running-frontend-tag> <verified-backup-dir> <compose-sha256> --dry-run`.
   The integration owner must separately review its host prerequisites and the write phase.
7. Only after all required gates pass, perform the approved switch, smoke the exact release and
   catalog, verify installed POSM/old QR traffic and monitor errors, disk/DB queues and writer counts.
   After new wallet writes, prefer roll-forward; freeze incompatible financial writers and reconcile
   before any rollback. Do not silently enable money, RPS, SMS or automation during a code switch.

## Local backup change verification

`tools/ops/private_backup.py` and `tools/private-files-backup.sh` add independent native/key
artifacts with strict private paths, SHA-256 manifests, safe isolated extraction and capacity checks.
The whole chain includes them before offsite copy. Native snapshots rotate with the verified count
policy only after complete success; key recovery archives are not locally pruned. Business source
files are never deleted. See `docs/20-reliability-and-release.md` for the exact operational contract.

Local Linux checks used inert Docker/restic fixtures and no network: 48 tests passed (including the
existing retention suites), full-chain flock/retention ordering passed, seven shell scripts passed
ShellCheck. Two additional isolated MinIO servers verified exact private/custom-prefix policy
roundtrip, including actual anonymous GET results 200/403/403. These validate code boundaries;
they do not replace a live offsite recovery drill or G4. The fresh runtime reference audit is a
separate aggregate; pending or pre-existing dangling references are not counted as restored.
