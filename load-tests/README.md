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
