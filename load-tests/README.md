# 500 cash-desk load test

The k6 model runs six independently selectable workloads against a disposable or staging stack:

- 500 heartbeats per minute;
- 500 playlist polls per 30 seconds;
- recommendation requests (20/s by default);
- completed sales (10/s by default);
- a synchronized recovery burst where all 500 desks replay durable offline events twice to verify
  idempotency;
- authenticated concurrent video uploads to MinIO.

Never use the full suite against production during business hours. The runner refuses the production
hostname unless the explicit impact acknowledgement is supplied.

```bash
BASE_URL=http://host.docker.internal:8080 \
POSM_DEVICE_KEY=dev-posm-key \
PHARMACY_IDS=ph_auezova_134,pharmacy-2 \
./load-tests/run-500-cash-desks.sh
```

Production-like staging should use individually provisioned credentials: create a mode-`0600`
file containing exactly 500 device keys, one per `load-kassa-001` … `load-kassa-500`, and set
`POSM_DEVICE_KEYS_FILE=/absolute/path/to/keys`. The runner mounts it read-only instead of exposing
the fleet credential as an environment variable. One shared key is supported only for an isolated
staging environment with the legacy enrollment window deliberately enabled.

Run upload pressure separately with a short-lived admin JWT:

```bash
SCENARIOS=video_uploads ADMIN_TOKEN='<access token>' VIDEO_SIZE_KB=10240 \
./load-tests/run-500-cash-desks.sh
```

The run fails when request errors reach 1% or the per-workload latency budgets are exceeded. A full
k6 summary is written to `load-tests/results/summary-<run-id>.json` without overwriting older runs;
correlate its timestamps with the Epharm Grafana dashboard. Use a restored production-size database
and object corpus for capacity sign-off.

For positive recommendation/playlist coverage, use the isolated fixture below and pass its
`background.expected.json` as `BACKGROUND_EXPECTATIONS_FILE`. This enables exact non-empty
rule/offer/slide checks, real recommendation-event writes, and explicit positive-workload counts;
otherwise the legacy suite only checks those routes' HTTP status. With expectations enabled the
fixture supplies trigger identifiers automatically. Set `K6_DOCKER_NETWORK=container:epharm-rps-load-backend`
and `BASE_URL=http://127.0.0.1:8080` to share an egress-disabled disposable backend's network.
The runner disables k6 usage reporting and retains p99 values. Offline-event IDs use the global
scenario iteration, so only the deliberate second request is a duplicate, even with 500 VUs.
The upload scenario transfers synthetic bytes labelled `video/mp4`; it tests authenticated upload
and storage pressure, **not playable-video decoding, rendering or physical customer displays**.

## Isolated RPS registration and session pressure

`run-rps-registration.sh` is a separate POSM/server test; it runs **no Flutter/mobile application
tests and makes no SMS requests**. It covers a 500-desk registration wave together with 500
heartbeats/minute, 500 playlist polls/30 seconds and 20 recommendation requests/second. The existing
six-workload suite above remains the separate sales/offline-replay/upload check.

This runner has no production override. Only explicit `localhost`, `127.0.0.1`, `[::1]` and
`host.docker.internal` origins are accepted, with redirects disabled. Do not forward a production
backend to one of those addresses. Use a fresh disposable backend/database with all outbound SMS,
catalogue, task bridges and other external integrations disabled. Enable only
`POSM_STAFF_AUTH_ENABLED=true` and `POSM_STAFF_REGISTRATION_SMS_REQUIRED=false` for these routes;
keep legacy shared-key enrolment disabled and provision the generated individual keys instead.
Do not print or commit fixture passwords, keys, HTTP request bodies or staff grants.

Generate a new owner-only directory **outside the repository**:

```bash
python3 load-tests/build-rps-fixture.py --run-id before --desks 500 \
  --output-dir /absolute/private/path/rps-before
```

This creates `fixture.private.json`, `seed.private.sql`, `verify.sql`, `device-keys.private.txt`,
`pharmacy-ids.txt` and `background.expected.json` with mode `0600`; it does
not contact HTTP or PostgreSQL. The default fixture deliberately uses **the same device ID `KASSA`
in 500 different pharmacies**, with 500 distinct individual credentials, valid synthetic IINs,
synthetic phones and passwords. This reproduces cross-pharmacy rate-limit collisions. To compare
with distinct terminal names, generate another disposable fixture with `--unique-device-ids`.

Migrate a fresh database named **exactly `epharm_rps_load_before`**, then apply `seed.private.sql`
using `psql -X -v ON_ERROR_STOP=1`. The seed and verification SQL reject any other database name.
The seed creates pharmacies, credential hashes, expected-profile rows and financial row-count
baselines. It also creates two local synthetic catalogue products, an active exact-product rule
with a known price/bonus, and a video slide in a dedicated playlist assigned to every fixture
pharmacy. It preserves `pl_broadcast`; no external catalogue/media fetch is used. The slide URL is
local synthetic metadata, so this API test does not claim video playback. **The seed does not
pre-create pharmacist accounts, delete data or call SMS**. Never apply it
to production or the working copy of production data. For a before/after comparison, recreate the
same disposable database and reapply the same seed, changing only the reviewed backend JAR.

The Docker k6 runner can reach a disposable backend published on the host, for example:

```bash
BASE_URL=http://host.docker.internal:18080 \
RPS_FIXTURE_FILE=/absolute/private/path/rps-before/fixture.private.json \
ISOLATED_RPS_LOAD_TEST=DISPOSABLE_DATABASE_NO_SMS \
VUS=20 DURATION=2m MODE=register \
bash load-tests/run-rps-registration.sh
```

For a backend inside an egress-disabled Docker network, set
`RPS_DOCKER_NETWORK=container:epharm-rps-load-backend` and
`BASE_URL=http://127.0.0.1:8080` instead; the k6 container then shares that disposable backend's
network namespace. The runner does not accept Docker's host network.

The original six-workload suite requires exactly 500 distinct `load-kassa-001` … `load-kassa-500`
device names. Generate a **separate 500-desk fixture with `--unique-device-ids`** for it, apply its
seed to the disposable database, pass `device-keys.private.txt` as `POSM_DEVICE_KEYS_FILE`, and use
the comma-separated pharmacy list from `pharmacy-ids.txt` as `PHARMACY_IDS`. The generated ordering
matches that suite's device/key/pharmacy indexing. Its fixed 500-desk model does not accept a
250-key fixture. Never use the shared-`KASSA` fixture with a runner claiming different device IDs.
Use that fixture's `background.triggerBarcode` as `TRIGGER_BARCODE` and `background.triggerSku`
as `TRIGGER_SKU`; its generated local rule then exercises real matching and event writes.

- `MODE=register` schedules one new profile per fixture desk across **60 seconds** and checks
  capabilities → registration → process-bound session → duplicate rejection → password login →
  session validation → logout. `SCHEDULE=burst` instead starts a bounded shared-iteration burst.
  For minute arrivals, `VUS` is preallocated capacity; generator headroom permits up to
  `max(VUS, 50)` VUs, capped by fixture desk count, so warm-up does not silently drop offered
  profiles. Both values are retained in the summary. `SCHEDULE=burst` keeps the fixed VU count:
  use 10, 20 or 50 VUs for controlled concurrency; a separate disposable run may use up to 500.
- `MODE=login` uses the profiles already created by a successful registration run. `LOGIN_ROUNDS`
  is 1 by default, at most 5. These are legitimate login rounds, not a password-guessing test;
  recreate the test database between repeated capacity comparisons rather than exhausting its
  unchanged account/device protection budgets.
- `MODE=sessions` bootstraps one real synthetic grant per desk outside the measured session
  pressure, then validates **500 sessions/30 seconds** for `DURATION`, alongside the same POSM
  background. Teardown logs out those grants. Bootstrap is limited to 5 minutes, teardown to 2;
  the default sustained scenario is 2 minutes. No infinite run or recovery loop is used.

Successful HTTP status alone is insufficient: each auth/session response must identify the same
profile, preserve the exact process context, contain a future expiry and (when issuing a grant)
a real token. Duplicate registration must be rejected. The summary separately counts **429**,
**5xx** and transport errors; all must be zero for this healthy-capacity profile. No request is
automatically replayed. Latency thresholds are per workload, request timeout matches POSM's 15
seconds, and dropped iterations/completed-profile counts prevent under-delivery from looking green.
The runner writes a fresh `results/rps-<mode>-<timestamp>-<pid>.json` for each run.
Playlist polls must return the exact seeded slide/URL/type/duration; recommendation requests must
return the exact seeded rule, trigger/product IDs, price, bonus and a real persisted-event ID.
Empty successful playlists/recommendation arrays fail the run. Summaries retain actual p95 and p99
values in addition to their threshold results.

After each successful phase, run the generated `verify.sql` with `psql -X -v ON_ERROR_STOP=1`.
It uses a read-only transaction and asserts exact profile count, active status, the device's
**correct pharmacy**, declared phone status, BCrypt password presence, no extra synthetic profiles,
no active load-test grants after logout, unchanged financial/seller-mapping row counts, the seeded
playlist assignments and at least one correctly quoted persisted recommendation event. This
SQL evidence is required: the session DTO intentionally does not expose pharmacy assignment.
Retain the k6 summary, SQL assertion result, resource limits, backend source/JAR hash, PostgreSQL
and JVM metrics and duration together. A small local fixture proves correctness under its stated
limits; it does not certify the capacity of a production-sized database or physical cash desks.

Guard/fixture checks make no network requests and need no containers:

```bash
node --test load-tests/rps-contract.test.mjs
python3 -m unittest discover -s load-tests -p 'test_rps_fixture.py' -v
bash -n load-tests/run-rps-registration.sh
```
