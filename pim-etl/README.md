# Standard N PIM ETL

This directory contains the recovered production pipeline that imports daily Standard N snapshots into ClickHouse and exports a complete, immutable catalogue handoff for a forced-command receiver. It is intentionally independent from the ePharm transactional database.

## Safety contract

- A source file is accepted only when its date filename, byte size, 20-column CP1251/TSV contract, every row, numeric value, pharmacy identity, and duplicate key checks pass.
- Invalid or blank product identifiers remain loss-accounted in PIM but never enter catalogue exports. No identifier is invented.
- Data is loaded into staging tables. Every live partition is backed up, replacement intent is journalled durably, and partial publication is rolled back or recovered on the next run. Terminal staging tables are removed immediately; rollback backups are retained for 72 hours by default and then cleaned idempotently.
- The catalogue is exported only from a completely published snapshot. Missing prices or expiry dates fail closed for sellability, and data older than 48 hours is not delivered.
- The receiver must independently match the protected host/user/port allowlist, uses a pinned `known_hosts` file and a dedicated key, and must acknowledge the exact snapshot and manifest hashes.
- Secrets live only in root-readable files under `/etc/pim-dashboard`. They are neither committed nor inserted into subprocess arguments.

The SSH catalogue receiver is not the Medusa commerce HTTP origin. Replacing the retired commerce origin remains a separate P0 operation requiring a current URL and keys.

## Layout

- `pim_validation.py` — complete-file validation and immutable artifact creation.
- `load_files.py` — ClickHouse staging, publication journal, rollback, exports, and orchestration.
- `pim_runtime.py` — secret-free SMB and ClickHouse runtime adapters.
- `pim_medusa_push.py` — forced-command delivery with endpoint pinning and acknowledgement validation.
- `clickhouse/` — bootstrap schema for a fresh ClickHouse database.
- `systemd/` — hourly import and Kazakhstan-midnight sellability rollover units.
- `examples/` — non-secret configuration shapes only.

## Verification

The unit suite is hermetic and does not need SMB, Docker, ClickHouse, or receiver credentials:

```sh
cd pim-etl
python3 -m compileall -q .
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

Production smoke checks must be read-only first: validate the protected file modes, list SMB files, run `SELECT 1` through the same service environment, then use `--validate-only` on one exact source. Do not enable the timer until those checks pass.

## Installation contract

The supplied units assume code is deployed read-only at `/opt/epharm/pim-etl` and state is stored at `/var/lib/pim-dashboard`. Install the three files under `examples/` as protected runtime files, replace all example values, and set these modes:

```text
/etc/pim-dashboard/pim-etl.env          0600 root:root
/etc/pim-dashboard/smb-credentials      0600 root:root
/etc/pim-dashboard/medusa-push.json     0600 root:root
/etc/pim-dashboard/receiver-key         0600 root:root
/etc/pim-dashboard/receiver-known-hosts 0644 root:root
```

Verify the receiver host key out of band before pinning it. The JSON receiver and `PIM_MEDUSA_ALLOWED_*` values must match; disagreement stops delivery. Apply `clickhouse/01_schema.sql` for a new database. Apply `02_filter_indexes.sql` only in a maintenance window because index materialization scans existing partitions.

The ETL ClickHouse identity needs `SELECT`, `INSERT`, `CREATE TABLE`, `ALTER TABLE`, and `DROP TABLE` only within `pharmacy_analytics`; it must not be reused by the read-only dashboard or public API.

After copying and reviewing the units, run `systemd-analyze verify` and enable both timers. Operational recovery is an isolated Git revert plus redeploy; published ClickHouse partitions remain protected by the durable backup/publication journal.
