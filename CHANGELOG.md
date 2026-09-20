# Changelog

All notable production changes to Epharm are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and release tags use semantic versioning.

## [Unreleased]

### Fixed

- Replaced the stale `0 pharmacies` campaign counter with the current global active-pharmacy target.
- Added an auditable POSM rollout coverage endpoint and admin warning for unprovisioned pharmacies.
- Made recommendation matching fast and resilient to local cash-register barcodes and safe name variants.
- Restart POSM once after device enrollment so every API immediately uses the new per-device credential.

### Added

- Production-grade PostgreSQL and MinIO backup, retention, isolated restore testing and backup metrics.
- Prometheus, Alertmanager, Grafana, exporters and actionable availability/SLO/capacity alerts.
- Sentry integration points for backend, admin frontend and Flutter mobile application.
- Immutable release identity, deploy verification and rollback tooling.
- A k6 workload model for a 500-cash-desk fleet.
- Automated mobile release gates, privacy manifest and deep-link declarations.

## [0.1.4] - 2026-09-19

### Fixed

- Replaced the generic promotion-rule save error with field-level, accessible validation and an
  actionable error summary.
- Normalized read-only Medusa product snapshots before saving so external catalog text cannot block
  campaign rule authoring.
- Added conditional validation for campaign goals and comparison rows while keeping barcode,
  iPartID and an unused goal optional.

## [0.1.3] - 2026-09-18

### Fixed

- Replaced slow, failure-prone remote Medusa search with an automatically refreshed PostgreSQL snapshot.
- Kept catalogue browsing and promotion product search available during Medusa outages.
- Added release smoke coverage for snapshot readiness and exact-name catalogue search.

## [0.1.2] - 2026-09-18

### Fixed

- Restored secure per-device POSM presence and the live register count during the credential migration.

## [0.1.1] - 2026-09-18

### Fixed

- Restored the live Medusa catalogue for admin, mobile and promotion workflows.
- Reduced Medusa listing latency, added last-known-good cache fallback and hourly linked-product refresh.
- Added release smoke gates that reject an empty production catalogue.

## [0.1.0] - 2026-09-08

### Added

- Initial versioned baseline for the existing backend, admin, mobile and POSM applications.
