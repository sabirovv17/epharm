# P0 Release Gates

This runbook separates repository readiness from credentials and infrastructure that only the
account owners can provide. A release is blocked until every gate below is green.

## 1. Medusa catalogue

Current repository state:

- the live catalogue is enabled by default; the 2026-09-18 smoke reported 28,503 products;
- the backend accepts HTTPS origins, loopback development, and exactly the current
  `http://78.140.246.238:9000` legacy origin; arbitrary remote HTTP remains rejected;
- enabling Medusa with incomplete identifiers aborts backend startup;
- connect/read deadlines default to 2/15 seconds and the listing uses a measured lightweight projection;
- a complete catalogue snapshot is persisted in PostgreSQL and refreshed hourly; incomplete/failed
  refreshes keep the last complete generation, while first-sync failures retry every five minutes;
- browsing and partial search (name/brand/MNN/SKU/EAN/category) run against the local trigram-indexed
  snapshot instead of Medusa's slow remote `q` filter;
- linked promo/POSM prices, images and barcodes refresh hourly at `:05` in `Asia/Almaty`.

Current public Store API identifiers (not administrator credentials):

```text
MEDUSA_BASE_URL=http://78.140.246.238:9000
MEDUSA_PUBLISHABLE_KEY=pk_ed9c35a59066b45de7d9e12510468ca27af16b6d0170b0910c03746545da4525
MEDUSA_SALES_CHANNEL_ID=sc_01KRXGQFYXMJN3FD1WJ7S83WME
MEDUSA_REGION_ID=reg_01KSBNEH2D4GVJN8EATK79WNSH
```

Validate from the production network before enabling it:

```bash
set -a
. ./.env.prod
set +a
./tools/smoke-medusa.sh
```

The smoke test must report a numeric catalogue count, a sample product id and a successful exact-name
search from the persisted snapshot. Deploy only if it passes, then verify the admin product picker,
mobile catalogue list, one product detail, one image
through `/api/media/img`, and one pharmacy-price response. Moving the origin behind HTTPS remains
an operations hardening task; do not add a general-purpose insecure-HTTP bypass.

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
