# Stock service

Read-only Standard-N Firebird collector, SQLite snapshot cache and Bearer JSON
API. One background worker visits every active pharmacy profile and records
collection progress. The source schema, API contract, measured performance
limits and deployment/rollback steps are in
[`docs/22-stock-service.md`](../docs/22-stock-service.md).
The machine-readable API contract is [`openapi.yaml`](openapi.yaml).

The service requires the variables in `.env.example`. Keep `.env` only on the
server with mode `0600`; it contains the Firebird password and API key.
`compose.yml` binds the API only to the host loopback address on
`STOCK_HOST_PORT` (default `18080`). Nginx on the stock host handles HTTPS;
the old gateway can proxy the stable API URL to it. Set `STOCK_HOST_DATA_DIR`
in the server `.env` to the absolute persistent SQLite directory when the Git
checkout differs from the data directory. The default full-sweep interval is
30 minutes because the measured indexed read rate cannot support a verified three-minute sweep of
all 586 profiles without a different source strategy. Use
`GET /stocks/api/v1/status` to inspect actual freshness.

## Developer handoff

- `Program.cs`: HTTP routes and response codes; all API routes use Bearer auth.
- `RefreshCoordinator.cs`: single-reader scheduler and per-profile failure handling.
- `Source/StandardNSource.cs`: parameterized, read-only Firebird SELECTs.
- `StockRepository.cs`: SQLite snapshots, active catalog and collection status.
- `compose.yml` and `.env.example`: deployment contract; real secrets are on the server.

Run `dotnet test stock-service-tests/stock-service-tests.csproj` before changes.
Read `docs/22-stock-service.md` before connecting another service. Persist the
Standard-N `profileId` and `sourceId` together to identify a warehouse row;
`partId` can repeat within a pharmacy.
