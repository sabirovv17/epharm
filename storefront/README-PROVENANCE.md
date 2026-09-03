# Source provenance

This directory is the source snapshot of the Inkar storefront previously
deployed from `/opt/inkar-shop/current` on the dedicated storefront host. It was
imported into the ePharm repository before implementing the order bridge so the
deployed application, migrations, worker and rollback scripts are versioned
together.

Runtime data, secrets, PostgreSQL files, uploads, generated caches, build output
and `node_modules` are intentionally excluded. Production configuration remains
under `/srv/inkar-shop/config` on the host and must never be committed.
