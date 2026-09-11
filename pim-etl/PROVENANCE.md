# Recovery provenance

The operational ETL bundle was read from the production deployment on 2026-09-11 before refactoring. The deployment directory did not contain Git metadata. These SHA-256 values bind the source evidence that was recovered:

| Deployed file | SHA-256 |
| --- | --- |
| `pim_validation.py` | `3b95438a0d31626d6864e01eff472dabe342dbe285845a0a300668475b9902bd` |
| `load_files.py` | `1abcdbedfe2345fcdde5f50f5e87013557d23abd842586ad023bb60d68cc9dd5` |
| `pim_medusa_push.py` | `3a21a9b019bee763e74e8985dc0aea53c739d22282aed6e5dda40e06f446d` |
| `test_pim_validation.py` | `eb84f9a36a67eed258de95692e77257e32bed774f0efecd3289cea37dcdf855d` |
| `test_pim_publication.py` | `a87556cd2f1b317343b3b1297d3af18547ce4b2cf5e409359e1f7f4cf4c3785e` |
| `test_pim_push.py` | `cee4f1cf68e161d20efd6accc95e6d49ff9f832b6edf322b9246801fd68001db` |
| `test_pim_rollover.py` | `6d60c48cb85e628c66113d60ab2af5d69c722c817caba6180f7128cecc1d0421` |

The Git version intentionally differs from those hashes. It removes the import of `load_files_legacy_20260907.py`, which contained deployment-specific credential configuration; adds `pim_runtime.py`; replaces the hard-coded receiver endpoint with a protected independent allowlist; strengthens path, permission, endpoint, and subprocess validation; and adds CI coverage. No credential file, private key, source CSV, validated snapshot, ClickHouse volume, or generated analytics artifact was copied into Git.
