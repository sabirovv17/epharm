#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/epharm-eshop-smoke.XXXXXXXX")"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/bin"

cat > "$fixture/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
output=''
previous=''
for arg in "$@"; do
  if [[ "$previous" == '--output' ]]; then output="$arg"; previous=''; continue; fi
  [[ "$arg" == '--output' ]] && { previous='--output'; continue; }
done
[[ -n "$output" ]] || exit 1
[[ " $* " == *' http://10.10.1.80:13105/health '* ]] || exit 1
[[ " $* " == *' Authorization: Bearer test-only-secret-with-at-least-32-characters '* ]] || exit 1
cp "$HEALTH_FIXTURE" "$output"
STUB
chmod +x "$fixture/bin/curl"

printf '%s\n' '{"status":"ok","schemaVersion":1,"catalogRunId":"00000000-0000-4000-8000-000000000001","catalogCount":29326,"availabilityRunId":null}' > "$fixture/healthy.json"
printf '%s\n' '{"status":"ok","schemaVersion":1,"catalogCount":0}' > "$fixture/broken.json"

export PATH="$fixture/bin:$PATH"
export ESHOP_CATALOG_BASE_URL='http://10.10.1.80:13105'
export ESHOP_CATALOG_TOKEN='test-only-secret-with-at-least-32-characters'
export HEALTH_FIXTURE="$fixture/healthy.json"
"$root/tools/smoke-eshop-catalog.sh" >/dev/null

export HEALTH_FIXTURE="$fixture/broken.json"
if "$root/tools/smoke-eshop-catalog.sh" >/dev/null 2>&1; then
  echo 'ERROR: invalid exporter health passed smoke' >&2
  exit 1
fi

export ESHOP_CATALOG_BASE_URL='http://10.10.1.99:13105'
if "$root/tools/smoke-eshop-catalog.sh" >/dev/null 2>&1; then
  echo 'ERROR: non-pinned HTTP origin passed smoke' >&2
  exit 1
fi
printf 'Private catalogue smoke contract OK\n'
