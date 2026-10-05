# Stock service

Read-only Standard-N Firebird collector, SQLite snapshot cache, authenticated
`/stocks/` page, JSON API and Excel export. The source schema, API contract,
freshness rules and deployment/rollback steps are in
[`docs/22-stock-service.md`](../docs/22-stock-service.md).

The service requires the variables in `.env.example`. Keep `.env` only on the
server with mode `0600`; it contains the Firebird password and separate web/API
credentials. `compose.yml` connects the service to the existing Caddy frontend
network and does not publish a port.
