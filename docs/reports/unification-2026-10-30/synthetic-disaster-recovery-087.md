# Encrypted synthetic disaster-recovery drill — 30 October 2026

**Passed:** a real PostgreSQL dump, native private file and separate credential-key archive were
backed up through a real encrypted local restic repository, recovered to fresh paths and restored
to a new isolated PostgreSQL16 database. This is a synthetic technical drill, **not** proof of
recovery from the live independent offsite repository.

Machine-readable evidence and immutable pins:
[synthetic-disaster-recovery-087.json](synthetic-disaster-recovery-087.json).

| Check                    | Result                                                                                                             |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| Source application       | Final JAR `df351555…02475`, quiesced by the acceptance owner                                                       |
| PostgreSQL               | Real custom dump; all 141 tables retain identical original-column row and PK hashes after recovery                 |
| Native files             | One actual synthetic evidence PNG, exact size/SHA256 restored                                                      |
| Credential keys          | Separate `credential-keys` artifact, restored privately with its original key ring                                 |
| Encrypted repository     | Official restic0.19.1 binary, checked against its published SHA256SUMS; full `check --read-data` passed            |
| Recovery secret          | Wrong restic password rejected; the recovery secret is outside all backed-up paths                                 |
| AEAD credential recovery | The exact final JAR's `LearnerCredentialCrypto` decrypted one restored copy                                        |
| Credential correctness   | Decrypted bytes match the private expected SHA256 and the restored current BCrypt hash                             |
| Missing/wrong AES keys   | Both fail closed, with no plaintext output                                                                         |
| Isolation                | New PostgreSQL container, network `none`, no published ports; no application or worker started on the recovered DB |

The backed-up paths contain only PostgreSQL and the two private archive kinds. Application
environment files, browser artifacts and downloaded plaintext credential XLSX files were excluded.
Passwords, codes, tokens and key values were not printed. The source synthetic lab and all preserved
source/candidate datasets remained unchanged. Private archives, encryption material and complete
logs remain outside Git.

The live release still requires a fresh complete backup from the actual configured independent
repository and an isolated restore using independently recoverable key custody. In particular,
the existing selected-bucket backup configuration must cover `pim-product-images` as well as
`epharm-receipts`; a separate verified file copy is not an offsite backup. This local repository
does not provide independent-host or site-loss protection.

Version-specific rollback compatibility is a separate test. The actual running v047 source already
contains `PharmacistWalletService`; the older opt-in v038 test limitation does not establish
incompatibility of v047. Startup, reads and controlled financial writes on the synthetic recovered
schema must be assessed against that exact binary. Neither this recovery drill nor a financial
compatibility result proves rollback of the newly introduced permissions/revocation model or
reversal of already completed business payments.
