#!/usr/bin/env bash
# Exercise the actual Caddyfile over HTTP, including the legacy QR query and
# deny-by-default routes. No CRM credential or real task token is used.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/epharm-caddy-redirect.XXXXXXXX")"
container="epharm-caddy-redirect-$$"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf -- "$test_dir"
}
trap cleanup EXIT INT TERM

mkdir -m 700 "$test_dir/tls"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=epharm.example.test' \
  -keyout "$test_dir/tls/private.key" \
  -out "$test_dir/tls/fullchain.pem" >/dev/null 2>&1

docker run -d --name "$container" \
  -p 127.0.0.1::8060 \
  -v "$root/Caddyfile:/etc/caddy/Caddyfile:ro" \
  -v "$test_dir/tls:/etc/caddy/tls:ro" \
  -e ADMIN_DOMAIN=epharm.example.test \
  -e API_DOMAIN=epharm.example.test \
  -e S3_DOMAIN=epharm.example.test \
  -e ACME_EMAIL=ci@example.test \
  -e MERCH_PORTAL_UPSTREAM=https://merch.example.test:8443 \
  caddy:2-alpine caddy run --config /etc/caddy/Caddyfile >/dev/null
docker exec "$container" caddy validate --config /etc/caddy/Caddyfile >/dev/null
port="$(docker port "$container" 8060/tcp | sed -n 's/.*://p' | head -n 1)"
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Caddy test port was not published' >&2; exit 1; }

request() {
  local method="$1" path="$2"
  curl --silent --show-error --connect-timeout 2 --max-time 5 \
    --request "$method" --header 'Host: epharm.example.test' \
    --header 'X-Forwarded-Proto: https' \
    --dump-header "$test_dir/headers" --output /dev/null \
    "http://127.0.0.1:$port$path"
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
