# Epharm: Current Context

Updated: 2026-07-14 (Asia/Almaty). This document is a handoff snapshot. It intentionally contains no passwords, API keys, JWTs, device keys, or private certificate material.

## Production

| Item                          | Value                                                                      |
| ----------------------------- | -------------------------------------------------------------------------- |
| Server                        | `inkpim.inkar.kz`, private address `10.10.1.76`                            |
| Application directory         | `/home/adm-quasar/epharm`                                                  |
| Compose stack                 | Caddy, frontend, Kotlin/Spring backend, PostgreSQL, Redis, MinIO           |
| Runtime config                | `/home/adm-quasar/epharm/.env.prod` (mode `0600`)                          |
| Admin URL, corporate DNS/VPN  | `https://epharm.inkar.kz/login`                                            |
| Admin URL, temporary fallback | `http://epharm.inkar.kz:8060/login`                                        |
| Intended public URL           | `https://epharm.inkar.kz/login` after the INKAR gateway host is configured |

The admin bootstrap account is `admin@epharm.kz`. Its current password and the POSM device key must be retrieved only from the protected production configuration or the approved secret store.

## Verified State

Last checked on 2026-07-14:

- All containers were running. Backend, frontend, PostgreSQL, Redis, and MinIO reported `healthy`; Caddy has no configured Docker healthcheck.
- Direct Caddy HTTPS through corporate DNS (`epharm.inkar.kz -> 10.10.1.76`) returned HTTP 200 for `/login` and `/api/health`.
- Caddy serves the INKAR-issued `*.inkar.kz` certificate with a complete three-certificate chain. OpenSSL verification returned `Verify return code: 0 (ok)`.
- The certificate is valid from 2025-12-17 through 2027-01-17. The private key is stored only in the root-owned server TLS directory with mode `0600`; the directory has mode `0700`.
- `http://epharm.inkar.kz:8060/login` returned HTTP 200.
- `http://epharm.inkar.kz:8060/api/health` returned HTTP 200.
- Admin authentication and `/api/admin/dashboard/summary` both returned HTTP 200 through Caddy.
- The Admin Storefront returned prices for 20/20 sampled Medusa products through the per-pharmacy price fallback.
- POSM authentication, the active playlist endpoint, and the playlist media file were verified through port `8060`.
- The active broadcast is one `video` slide; its HTTP media URL through `/s3/...` returned HTTP 200.
- The only recent backend log error was a client-side broken pipe, not an application failure.

## External Public HTTPS Is Not Ready

The application server now has working HTTPS, but the external INKAR gateway is still not configured for this host. Public DNS resolves through `inkservices.trafficmanager.net` to `2.133.92.203`. That endpoint currently serves an expired `*.inteq.kz` certificate (expired 2026-05-23) and returns HTTP 404 for both `/login` and `/api/health` with `Host: epharm.inkar.kz`.

IT must configure the existing host-based HTTPS ingress:

```text
Public:  https://epharm.inkar.kz
Upstream: http://10.10.1.76:8060
TLS:     valid *.inkar.kz certificate and complete chain
Headers: Host: epharm.inkar.kz
         X-Forwarded-Proto: https
         X-Forwarded-For: <client IP>
```

The preferred gateway setup is TLS termination on `2.133.92.203` with the issued `*.inkar.kz`
certificate, matching private key, and CA bundle, then HTTP proxying to `10.10.1.76:8060`. Private-key
material must be transferred to IT only through the approved secure channel, not email or chat. An
alternative is TCP/TLS passthrough to `10.10.1.76:443`, where Caddy already serves the valid chain.

After acceptance, verify:

```text
GET https://epharm.inkar.kz/login       -> HTTP 200
GET https://epharm.inkar.kz/api/health  -> HTTP 200
```

Port `8060` is a temporary plain-HTTP fallback and the trusted upstream for the external ingress. It exposes admin credentials, POSM device keys, and POSM traffic without TLS when accessed directly. After external HTTPS is accepted, restrict direct access to the ingress/trusted internal network. SSH must remain restricted to the VPN/administrator network.

## POSM

Use only the current package:

| Item                      | Value                                                                  |
| ------------------------- | ---------------------------------------------------------------------- |
| Package                   | `/Users/amir/Desktop/work/epharm-demo/Epharm-POSM-v1.0.29-win-x64.zip` |
| SHA-256                   | `271623a06714537f5feb3655887a2f2a935a11e05e9338ad10d9c40d6022167c`     |
| Preferred backend origin  | `https://epharm.inkar.kz`                                              |
| Temporary fallback origin | `http://epharm.inkar.kz:8060`                                          |
| Package pharmacy id       | `sloc_01KSAHYDSJ5BCQT07391P1T8D7`                                      |

`v1.0.29` contains a POSM device key matched against production. Do not deploy `v1.0.27` or `v1.0.28`: they have obsolete/mismatched configuration.

The POSM client prefers HTTPS, switches to `:8060` after a TLS/network/gateway failure, and probes HTTPS again every five minutes. It also rewrites configured media origins to the active endpoint, so the current playlist can be fetched over the HTTP fallback.

Source changes implementing this behavior:

- `App/Config/EpharmConfig.cs`: `BackendFallbackBaseUrls` plus `EPHARM_BACKEND_FALLBACK_URLS`.
- `App/Services/BackendFailoverHandler.cs`: retry/failover handler.
- `App/Services/EpharmApiClient.cs`: uses the failover handler.
- `App/MainWindow.Screen.cs`: media URL rewrite to the active origin.
- `App/CustomerDisplay.csproj`: version `1.0.29`.

For a monoblock installation, server-side connectivity is verified. Device-side conditions still need verification on the target Windows machine:

1. It can reach `epharm.inkar.kz:8060` until HTTPS is ready.
2. The supplied `pharmacyId` is the actual pharmacy. Change it in `posm.json` for a different location.
3. Standard-N `zkassa.log` and Firebird `ztrade` are available at the configured/default paths.
4. The second monitor and Windows scheduled tasks are configured for `screenMode: prod`.

The POSM update endpoint currently returns HTTP 200 but has no current version. Initial installation must be manual. To enable automatic updates, host the ZIP at an HTTPS URL and register it as the current `win-x64` release through `/api/admin/app-releases` with the version and SHA-256.

## Medusa Pricing

Production Medusa is configured as the external source of catalog data. Observed state:

- Total catalog reported by Medusa: 27,975 products.
- In a sample of 100 products, `variants.calculated_price` was populated for 0 products.
- In a sample of 20 products, `pharmacy_pricing` contained a positive price/range for all 20.
- Admin Storefront passes `includeRetailFallbackPrices=true`, therefore its product prices render from `pharmacy_pricing` (median plus min/max range).
- The public mobile catalog currently does not enable this fallback, so it can still show `price: null` until that behavior is enabled in `MobileCatalogController` / `MobileCatalogService`.

## Remaining Work

1. IT: fix the HTTPS ingress and attach the valid `*.inkar.kz` certificate.
2. Recheck public HTTPS before restricting port `8060`.
3. Install POSM `v1.0.29` on one target monoblock and run a real Standard-N scan, playlist playback, heartbeat, and recommendation smoke test.
4. Publish/register the POSM ZIP for automatic updates after HTTPS hosting is available.
5. Decide whether mobile catalog pages should use the same `pharmacy_pricing` fallback as Admin Storefront.
6. Upgrade `SQLitePCLRaw.lib.e_sqlite3` from `2.1.11`; the .NET build reports its high-severity advisory `GHSA-2m69-gcr7-jv3q`.
7. Rotate operational credentials that were shared during deployment, store them in the approved secret manager, and remove them from chat/history where possible.

## Local Validation

The POSM project builds successfully on the current workstation with:

```bash
dotnet build App/CustomerDisplay.csproj -p:EnableWindowsTargeting=true --no-restore
```

The package ZIP passed `zip -T`, contains `CustomerDisplay/1.0.29`, and its `posm.json` was checked against the production device key without exposing that key.
