# Actual v047 binary on forward schema087 — synthetic compatibility proof

**Passed for the tested paths:** the exact JAR extracted read-only from the running v047 release
started on the new synthetic disaster-recovery database at Flyway087, read the current wallet and
performed controlled ledger adjustments. Results and container pins:
[v47-forward087-synthetic-compatibility.json](v47-forward087-synthetic-compatibility.json).

The binary SHA256 is
`a2a9187063001c8faf3a687c8266331ba3f4580ba7670eac5cf23dd85d96cfac`, from commit
`aa37b1e720923334cef8265f66a507d549b32780`. The application used a cached JVM runtime image,
synthetic test configuration and only the restored database's loopback network namespace.
The production image/environment and the live database were not started or changed by this test.

Verified with real HTTP and persisted database checks:

- Healthy startup with the forward V066–V087 additions and the schema remaining087.
- Legacy HQ and learner login, followed by `/api/mobile/me/balance` matching the restored balance.
- A synthetic HQ target-balance adjustment of +17 ₸ creates exactly one immutable ledger posting.
- Repeating that same target balance creates no additional posting.
- A compensating -17 ₸ adjustment creates a second immutable posting, returning the spendable
  balance to its initial value without deleting history or changing the earned projection.
- A final learner wallet read matches that initial balance.

These actions occurred **after** the successful recovery/hash proof and intentionally changed only
its disposable synthetic clone. The original quiesced acceptance lab and all preserved source/
candidate databases were not modified. The compatibility app was stopped using its recorded
container identity; no port was published and no external network/payment/SMS was available.

The actual v047 source already contains `PharmacistWalletService`. A limitation of the unrelated
older opt-in v038 rollback test does not show that v047 cannot operate with the current ledger.
Conversely, this small writer/read proof does not cover every reward, payout, provider or
concurrent workflow, nor does a compensating internal adjustment reverse a bank payment.

**Authorization rollback remains separate:** v047 does not implement the new managed grants,
data scopes and fresh authority/revocation checks. This financial/schema result must not be treated
as approval to expose newly restricted accounts through the old authorization model. A real
rollback requires its own access containment and exact live release/configuration checks.
