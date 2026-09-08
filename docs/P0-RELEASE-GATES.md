# P0 Release Gates

This runbook separates repository readiness from credentials and infrastructure that only the
account owners can provide. A release is blocked until every gate below is green.

## 1. Medusa catalogue

Safe repository state:

- the retired cleartext origin is not a runtime default;
- `MEDUSA_ENABLED=false` is the production default, so catalogue calls return immediately;
- enabling Medusa with an incomplete or non-HTTPS configuration aborts backend startup;
- connect/read deadlines default to 2/6 seconds;
- storefront reads, media proxy and mutation scripts reject remote HTTP origins.

Inputs required from Medusa operations:

```text
MEDUSA_BASE_URL=https://<reachable-origin>
MEDUSA_PUBLISHABLE_KEY=<publishable-store-key>
MEDUSA_SALES_CHANNEL_ID=<sales-channel-id>
MEDUSA_REGION_ID=<region-id>
```

Validate from the production network before enabling it:

```bash
set -a
. ./.env.prod
set +a
./tools/smoke-medusa.sh
```

The smoke test must report a numeric catalogue count and a sample product id. Then set
`MEDUSA_ENABLED=true`, deploy, and verify the mobile catalogue list, one product detail, one image
through `/api/media/img`, and one pharmacy-price response. Do not enable the flag if the smoke test
fails or requires an HTTP/TLS bypass.

## 2. Apple distribution and TestFlight

Repository-side signing is described in [IOS-DISTRIBUTION.md](IOS-DISTRIBUTION.md). The release owner
must provide a paid Apple Developer team that owns `kz.pharmacy.app`, accept current agreements, and
install an Apple Distribution identity/provisioning access in Xcode.

The old `Epharm-iOS-0.1.1+2-Runner.app.zip` is a Personal Team development build. Its stated profile
expiry was 2026-08-24, so it is expired and must not be distributed.

## 3. GitHub merge protection

The only required status context is `P0 / merge gate`. It aggregates ops/config contracts, static P0
policy, both web applications, backend, Flutter, POSM and commitlint. `main` must have strict branch
protection with administrator enforcement, force-push and deletion disabled.

After the account billing lock is cleared:

```bash
gh workflow run CI
gh run watch
gh api repos/sabirovv17/epharm/branches/main/protection
```

Do not merge based on local results alone. The head commit of the PR must have a successful
`P0 / merge gate` check.

## 4. Local preflight

```bash
./tools/check-p0-config.sh
(cd admin-panel/backend && ./gradlew build)
(cd admin-panel/frontend && npm ci && npm audit --audit-level=low && npm run lint && npm test && npm run build)
(cd storefront && npm ci && npm audit --audit-level=low && npm run lint && npm test && npm run build)
flutter analyze lib test && flutter test
dotnet test App.Tests/CustomerDisplay.Core.Tests.csproj -c Release
```

Local checks support diagnosis; the protected GitHub gate remains the merge authority.
