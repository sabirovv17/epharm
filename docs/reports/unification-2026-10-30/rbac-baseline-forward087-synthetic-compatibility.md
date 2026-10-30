# Existing RBAC baseline on forward schema087 — bounded synthetic check

**Passed in the isolated recovered synthetic database:** the existing pre-D9 local artifact
`32dd434e8fe2c6bbc2da7665c8f5f52e7309104917456dbeb13910486b561cc8`, identified by its owner
as the `0bc8d24` baseline, started with the forward schema087. No new artifact was built.
Machine-readable evidence:
[rbac-baseline-forward087-synthetic-compatibility.json](rbac-baseline-forward087-synthetic-compatibility.json).

Actual HTTP and persisted-state checks established:

- Managed HQ pharmacy lists contain only the assigned pharmacy; a foreign direct ID returns404.
- A stored newer D9 grant remains present in the capability response and unchanged in the DB
  after reads and denials. It does not expand the known view scope or expose personal fields.
  The unsupported credential-export endpoint is denied and produces no export job.
- Revoking the known view grant takes effect with the same JWT; restoring it restores access.
  Blocking the account also denies the same existing token.
- Ordinary learner profile access returns200, the wallet remains unchanged, and a learner token
  cannot enter administrative pharmacy routes.

The test used only the previously created disposable DR clone, its loopback network namespace and
private synthetic credentials. No port or external network was available. Its temporary scoped
profile values were restored with an increasing version; the owned baseline app was stopped.
The original acceptance source, preserved data candidates and production were not changed.

**This is not a ready or promoted production fallback.** An existing local JAR is not an immutable
approved release image, promoted source or successful exact-release CI/cutover gate. The check
does not cover every module, the old permissions editor, deployment configuration or live
rollback. Unsupported D9 remains unavailable in this baseline; this result only narrows the
compatibility uncertainty for a possible separately approved RBAC-preserving recovery artifact.
