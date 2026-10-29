# Fresh offline database preservation rehearsal — 30 October 2026

The new candidate passed the migration and inert-staging rehearsal. This is **not** a production
deployment or acceptance of the native functional import. Machine-readable pins, aggregate hashes
and the original table-by-table row/primary-key checksums are in
[fresh-database-preservation-087.json](fresh-database-preservation-087.json).

| Check                          | Verified result                                                                                         |
| ------------------------------ | ------------------------------------------------------------------------------------------------------- |
| `.76` source dump SHA256       | `9c12cbf910f7269cf8003553be9f9d2c4e736f8d69a628e682e243784ab127c2`                                      |
| PostgreSQL                     | 16.14, UTF8, `en_US.utf8`, libc                                                                         |
| Fresh target                   | `epharm-unify-candidate-20261030-d9-10e63b28`, database `epharm_unified_candidate`                      |
| Isolation                      | Private host-bound PGDATA, Docker network `none`, no published ports                                    |
| Original source                | 73 physical public tables, including Flyway history                                                     |
| Business preservation          | All 72 original business tables, 1,932,894 rows                                                         |
| Plain Flyway                   | 22 migrations, V066–V087; successful current version 087                                                |
| Public schema after migrations | 141 tables                                                                                              |
| Fresh CRM typed staging        | 57 tables, 42,414 rows; input SHA256 `682ad2cfdd784e59048e08e97dd1be1a56434c5b7a907e215c10e3ae5648d1d0` |
| CRM repeated intake            | `verified_existing_noop`; identical typed table checksums                                               |

The original-column row hashes, full primary-key definitions and primary-key value hashes match
before and after the migrations and again after CRM staging/replay. Original column definitions
also match. Added columns and new native tables are allowed; the expected change to Flyway history
is kept separate from the 72 business-table invariants. Both input artifacts remained unchanged.

The CRM source uses libc locale spelling `en_US.UTF-8`; the target preserves the exact `.76`
spelling `en_US.utf8`. The narrowly reviewed spelling-alias guard was exercised on the real typed
artifact. Source/target locale metadata and the recognized alias are recorded in the JSON report;
encoding, provider and all row/primary-key hash checks remain mandatory.

The runner invoked Flyway directly with a pinned JRE image and read-only migration/library mounts.
An initial nested-mount setup failure happened before Java started; its private log was retained,
the unchanged baseline was rechecked, and only this newly created candidate was retried. The
successful migration result is recorded separately. No Spring application, scheduler, native
delivery, reward replay, account creation or SMS was started. Existing source/previous candidate
databases and production were not changed. Native owned projections and the later functional,
file-reference, credential-key recovery and cutover gates remain separate checks.
