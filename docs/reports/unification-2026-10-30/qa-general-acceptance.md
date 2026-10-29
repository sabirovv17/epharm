# General acceptance: isolated runtime

The machine-readable result is `qa-general-acceptance.json`. This report describes an engineering rehearsal with synthetic data; it does not approve a deployment or close the complete acceptance gates in `PLAN.md`.

Seven unique live flows passed across focused runs on the final pinned backend: five base flows, the POSM wire flow and the D9 retry. The D9 browser flow found a real selection blocker when HQ lacked training permission. Root separated selection capability from training actions, then the actual browser download passed. Historical diagnostics remain recorded; this is not labelled a single clean seven-test run. Runtime/transport/provider checks passed 12/12 and .NET core passed 300/300.

`tools/unification/acceptance_runtime.py` creates a new empty PG16 database, Redis and application on a dedicated Docker **internal** network. Cached images and the supplied JAR SHA256 are pinned. The runtime refuses preserved/source database names, changed container identities, additional networks and non-loopback API URLs. It never copies a source account or starts a preserved candidate application. Its `stop` command stops only its own pinned resources and retains private evidence.

The app uses the normal authentication, access, business services, migrations and HTTP routes. The only provider replacement is `acceptance_otp.py`: an HTTPS Daribar protocol fixture on the same internal network, with an explicit synthetic phone allowlist and a separate synthetic Java truststore. There is no external SMS or catalog traffic. Passwords, JWTs, vault keys, database configuration and downloaded credential workbooks remain in an owner-only private directory.

Docker Desktop did not publish a port from this internal network: the app listened on all interfaces and was reachable from its PG neighbour, while `NetworkSettings.Ports` remained empty. The bounded host loopback proxy therefore forwards actual requests through `docker exec curl` into the pinned application. It preserves binary bodies/status/headers, strips hop-by-hop headers and follows no redirects. It supplies no application responses.

The opt-in browser suite is `admin-panel/frontend/artifact-tests/unified-acceptance.spec.ts`. API responses come from the real application. Browser requests outside the selected loopback frontend origin are blocked. Traces, automatic screenshots and video are disabled; private test output is created with umask `077`.

Reproduction uses a freshly built JAR and a built frontend preview on `127.0.0.1:4177`:

```sh
umask 077
python3 tools/unification/acceptance_runtime.py up \
  --jar admin-panel/backend/build/libs/app.jar \
  --jar-sha256 APPROVED_SHA256 --private-root NEW_OWNER_ONLY_DIRECTORY

# From admin-panel/frontend; fixture/output paths belong to that new private directory.
UNIFIED_ACCEPTANCE_FIXTURE=PRIVATE_FIXTURE_JSON \
ADMIN_ARTIFACT_BASE_URL=http://127.0.0.1:4177 \
npx playwright test --config playwright.artifact.config.ts \
  artifact-tests/unified-acceptance.spec.ts --reporter=list --output=PRIVATE_OUTPUT

python3 tools/unification/acceptance_runtime.py proof --manifest PRIVATE_MANIFEST
# Stop only the app/HTTP transport before the separate synthetic recovery rehearsal.
python3 tools/unification/acceptance_runtime.py quiesce --manifest PRIVATE_MANIFEST
python3 tools/unification/acceptance_runtime.py stop --manifest PRIVATE_MANIFEST
```

The harness exercises explicit HQ provisioning/grants, scoped MAS and regional access, current-pharmacy training scope, producer login/plan approval, separate learner login, evidence submission/independent review, selected XLSX downloads, revocation without relogin, D9 password proof/one-time download, and individual-device POSM contracts. Persistence evidence is read back as aggregate counts; HTTP 200 alone is insufficient.

The .NET core test result covers cross-platform client logic, linked historical HTTP/DTO code and synthetic loopback contracts. It cannot demonstrate a WPF window, a Standard-N scan, a customer monitor, an installed fleet update, a physical QR scan or a sale at a real checkout. Likewise, a synthetic `shown` acknowledgement or heartbeat is not proof that a popup was visible.

The source inventory and POSM contract map are traceability inputs, not a count of executed acceptance scenarios. The rehearsal does not check every action, source row/file/history, real payment, role combination, report total or human business workflow. Existing source preservation, production recovery, Windows/checkout acceptance and business sign-off need their own evidence.
