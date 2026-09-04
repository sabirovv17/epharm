#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_ROOT"' EXIT

command -v bash >/dev/null 2>&1 || { printf 'bash is required\n' >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { printf 'docker is required\n' >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { printf 'python3 is required\n' >&2; exit 1; }
docker compose version >/dev/null

while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(find "$SCRIPT_DIR" -type f -name '*.sh' -print0)

for harness in "$SCRIPT_DIR"/tests/*-harness.sh; do
  bash "$harness"
done

mkdir -p "${TEMP_ROOT}/data"
APP_ENV_FILE="${SCRIPT_DIR}/app.env.example" \
POSTGRES_ENV_FILE="${SCRIPT_DIR}/postgres.env.example" \
DATA_ROOT="${TEMP_ROOT}/data" \
CADDYFILE_PATH="${SCRIPT_DIR}/Caddyfile.http" \
docker compose \
  --env-file "${SCRIPT_DIR}/release.env.example" \
  --file "${SCRIPT_DIR}/compose.yml" \
  config --format json >"${TEMP_ROOT}/compose.json"

python3 - "${TEMP_ROOT}/compose.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    compose = json.load(source)

worker = compose["services"]["epharm-order-worker"]
networks = set(worker.get("networks", {}))
if networks != {"backend", "egress"}:
    raise SystemExit("epharm-order-worker must have private database and dedicated outbound networks")
if compose["networks"]["backend"].get("internal") is not True:
    raise SystemExit("backend network must remain internal")
if compose["networks"]["egress"].get("internal") is True:
    raise SystemExit("egress network must allow controlled outbound traffic")
health = " ".join(worker["healthcheck"]["test"])
if "epharm-order-worker.success" not in health:
    raise SystemExit("enabled order worker healthcheck must require a successful synchronization cycle")
PY

printf 'Inkar deployment package validation passed\n'
