#!/usr/bin/env bash
# Exercise the actual Caddyfile over HTTP, including the legacy QR query and
# deny-by-default routes. No CRM credential or real task token is used.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
caddyfile="${CADDYFILE_UNDER_TEST:-$root/Caddyfile}"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/epharm-caddy-redirect.XXXXXXXX")"
container="epharm-caddy-redirect-$$"
frontend="epharm-caddy-frontend-$$"
network="epharm-caddy-cutover-$$"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker rm -f "$frontend" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf -- "$test_dir"
}
trap cleanup EXIT INT TERM

mkdir -m 700 "$test_dir/tls"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=epharm.example.test' \
  -keyout "$test_dir/tls/private.key" \
  -out "$test_dir/tls/fullchain.pem" >/dev/null 2>&1

docker network create "$network" >/dev/null
cat > "$test_dir/frontend.py" <<'PY'
from http.server import BaseHTTPRequestHandler, HTTPServer
import json, threading
class Handler(BaseHTTPRequestHandler):
    writes = 0
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'path':self.path,'integrationHeader':self.headers.get('X-Pharmapay-Key'),
                                    'writes':Handler.writes}).encode())
    def do_POST(self):
        Handler.writes += 1
        self.do_GET()
    do_PUT = do_PATCH = do_DELETE = do_POST
    def log_message(self,*args): pass
threading.Thread(target=HTTPServer(('0.0.0.0',8080),Handler).serve_forever,daemon=True).start()
HTTPServer(('0.0.0.0',80),Handler).serve_forever()
PY
docker run -d --name "$frontend" --network "$network" --network-alias frontend --network-alias backend \
  -v "$test_dir/frontend.py:/app/frontend.py:ro" python:3.12-slim python /app/frontend.py >/dev/null

start_caddy() {
docker run -d --name "$container" --network "$network" \
  -p 127.0.0.1::8060 -p 127.0.0.1::80 \
  -v "$caddyfile:/etc/caddy/Caddyfile:ro" \
  -v "$test_dir/tls:/etc/caddy/tls:ro" \
  -e ADMIN_DOMAIN=epharm.example.test \
  -e API_DOMAIN=epharm.example.test \
  -e S3_DOMAIN=epharm.example.test \
  -e INTERNAL_DOMAIN=epharm-internal.example.test \
  -e ACME_EMAIL=ci@example.test \
  -e MERCH_PORTAL_UPSTREAM=https://merch.example.test:8443 \
  -e MERCH_LEGACY_PORTAL_CUTOVER_ENABLED="$1" \
  -e UNIFIED_MIGRATION_WRITE_FREEZE_ENABLED="${2:-false}" \
  caddy:2-alpine caddy run --config /etc/caddy/Caddyfile >/dev/null
docker exec "$container" caddy validate --config /etc/caddy/Caddyfile >/dev/null
port="$(docker port "$container" 8060/tcp | sed -n 's/.*://p' | head -n 1)"
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Caddy test port was not published' >&2; exit 1; }
internal_port="$(docker port "$container" 80/tcp | sed -n 's/.*://p' | head -n 1)"
[[ "$internal_port" =~ ^[0-9]+$ ]] || { echo 'Internal Caddy test port was not published' >&2; exit 1; }
}
start_caddy false

request() {
  local method="$1" path="$2" host="${3:-epharm.example.test}" target_port="${4:-$port}"
  curl --silent --show-error --connect-timeout 2 --max-time 5 \
    --request "$method" --header "Host: $host" \
    --header 'X-Forwarded-Proto: https' \
    --header 'X-Pharmapay-Key: must-never-reach-browser-upstream' \
    --dump-header "$test_dir/headers" --output "$test_dir/body" \
    "http://127.0.0.1:$target_port$path"
}
for _ in $(seq 1 20); do
  if request GET '/merch/staff?task=legacy-probe%2B1'; then break; fi
  sleep 0.2
done

python3 - "$test_dir/headers" <<'PY'
import sys

lines = open(sys.argv[1], encoding='latin-1').read().replace('\r\n', '\n').split('\n')
headers = dict(line.split(':', 1) for line in lines if ':' in line)
headers = {key.lower(): value.strip() for key, value in headers.items()}
assert lines[0].startswith('HTTP/1.1 302 '), lines[0]
assert headers.get('location') == 'https://crm.inkar.kz/staff?task=legacy-probe%2B1'
assert headers.get('referrer-policy') == 'no-referrer'
assert 'no-store' in headers.get('cache-control', '').lower()
PY
echo 'Caddy legacy query redirect: OK'

request GET '/merch/staff'
python3 - "$test_dir/headers" <<'PY'
import sys

lines = open(sys.argv[1], encoding='latin-1').read().replace('\r\n', '\n').split('\n')
headers = dict(line.split(':', 1) for line in lines if ':' in line)
headers = {key.lower(): value.strip() for key, value in headers.items()}
assert lines[0].startswith('HTTP/1.1 302 '), lines[0]
assert headers.get('location') == 'https://crm.inkar.kz/staff'
PY
echo 'Caddy queryless redirect: OK'
request POST '/merch/staff?task=legacy-probe'
grep -q '^HTTP/1.1 404 ' "$test_dir/headers"
request GET '/merch/admin'
grep -q '^HTTP/1.1 404 ' "$test_dir/headers"
echo 'Caddy legacy staff redirect and deny-by-default routes: OK'

docker rm -f "$container" >/dev/null
start_caddy true
for entry in '/merch/staff?task=synthetic-capability' '/staff?task=1'; do
  request GET "$entry"
  python3 - "$test_dir/headers" "$test_dir/body" <<'PY'
import json,sys
from urllib.parse import urlsplit
raw=open(sys.argv[1]).read().lower()
assert raw.startswith('http/1.1 200 '), raw
assert 'location:' not in raw and 'no-store' in raw and 'no-referrer' in raw
body=json.load(open(sys.argv[2]))
assert urlsplit(body['path']).path=='/tasks/staff' and not urlsplit(body['path']).query
assert body['integrationHeader'] is None
PY
done
for entry in '/merch/staff' '/staff'; do
  request GET "$entry"
  grep -q '^HTTP/1.1 302 ' "$test_dir/headers"
done
for entry in '/merch/api/public/task-dispatch/submit' '/merch/api/public/task-dispatch/evidence'; do
  request POST "$entry"
  grep -q '^HTTP/1.1 409 ' "$test_dir/headers"
  python3 - "$test_dir/body" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['code']=='MERCH_PORTAL_MOVED'
PY
done
request GET '/merch/api/task-files/synthetic-photo'
grep -q '^HTTP/1.1 409 ' "$test_dir/headers"
request GET '/merch/admin'
grep -q '^HTTP/1.1 404 ' "$test_dir/headers"
request POST '/staff?task=synthetic'
grep -q '^HTTP/1.1 404 ' "$test_dir/headers"
echo 'Caddy native cutover: same-origin SPA, no forwarded capability/key, old cached mutations fail closed: OK'

# A false/default flag must preserve ordinary writes on both ingress paths.
for host in epharm.example.test epharm-internal.example.test; do
  target_port="$port"; [[ "$host" != epharm-internal.example.test ]] || target_port="$internal_port"
  request POST /api/admin/promo "$host" "$target_port"
  grep -q '^HTTP/1.1 200 ' "$test_dir/headers"
done
request GET /api/health
before="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["writes"])' "$test_dir/body")"

docker rm -f "$container" >/dev/null
start_caddy true true
for host in epharm.example.test epharm-internal.example.test; do
  target_port="$port"; [[ "$host" != epharm-internal.example.test ]] || target_port="$internal_port"
  for method in POST PUT PATCH DELETE; do
    for path in /api/admin/promo /api/mobile/auth/password/reset /api/producer/placements /api/posm/staff/login; do
      request "$method" "$path" "$host" "$target_port"
      grep -q '^HTTP/1.1 503 ' "$test_dir/headers"
      grep -qi '^Retry-After: 30' "$test_dir/headers"
      grep -qi '^Cache-Control: no-store' "$test_dir/headers"
      python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["code"]=="MIGRATION_WRITE_FREEZE"' "$test_dir/body"
    done
  done
  for path in /api/health /api/mobile/catalog/products /api/admin/promo; do
    request GET "$path" "$host" "$target_port"
    grep -q '^HTTP/1.1 200 ' "$test_dir/headers"
  done
  for path in /api/posm/sales /api/posm/heartbeat /api/posm/recommendations/outcome; do
    request POST "$path" "$host" "$target_port"
    grep -q '^HTTP/1.1 200 ' "$test_dir/headers"
  done
done
request GET /api/health
after="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["writes"])' "$test_dir/body")"
[[ "$after" == "$((before + 6))" ]] || { echo 'A frozen write reached an upstream' >&2; exit 1; }
echo 'Caddy initial migration: both ingresses freeze mutations, reads and core POSM continue, no blocked write reaches upstream: OK'
