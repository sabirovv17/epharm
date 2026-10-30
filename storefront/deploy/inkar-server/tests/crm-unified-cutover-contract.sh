#!/usr/bin/env bash
# Actual edge transport, isolated stub services; no source/production connection.
set -euo pipefail
bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
network="epharm-crm-edge-$$"
proxy="epharm-crm-edge-proxy-$$"
stub="epharm-crm-edge-stub-$$"
cleanup() {
  docker logs "$proxy" >"$tmp_dir/proxy.log" 2>&1 || true
  docker rm -f "$proxy" "$stub" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT INT TERM
umask 077
cat > "$tmp_dir/stub.py" <<'PYTHON'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body=json.dumps(dict(path=self.path,host=self.headers.get('Host'),
            origin=self.headers.get('Origin'),proto=self.headers.get('X-Forwarded-Proto'),
            key=self.headers.get('X-Pharmapay-Key'),staff=self.headers.get('X-Staff-Session'),forwarded=self.headers.get('X-Forwarded-For'))).encode()
        self.send_response(200); self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    do_POST=do_PUT=do_PATCH=do_DELETE=do_GET
    def log_message(self,*args): pass
ThreadingHTTPServer(('0.0.0.0',8060),Handler).serve_forever()
PYTHON
docker network create --internal "$network" >/dev/null
docker run -d --name "$stub" --network "$network" --network-alias native \
  -v "$tmp_dir:/app" python:3.12-slim python /app/stub.py >/dev/null
# Use the very same native routes in HTTP and internal-TLS files.
python3 - "$bundle_dir" <<'PYTHON'
from pathlib import Path
import sys
root=Path(sys.argv[1])
a=(root/'Caddyfile.http').read_text(); b=(root/'Caddyfile.crm-internal-tls').read_text()
start='(unified_crm_proxy) {'; end='# The public gateway terminates TLS'
assert a[a.index(start):a.index(end)].strip()==b[b.index(start):b.index('# Preserve the gateway')].strip()
assert a.count('import unified_crm_routes http\n')==1
assert b.count('import unified_crm_routes http\n')==1 and b.count('import unified_crm_routes https\n')==1
PYTHON
docker run -d --name "$proxy" --network "$network" --network-alias proxy \
  -e SITE_DOMAIN=shop.example.test -e SERVER_IP=10.10.1.80 \
  -e EPHARM_UNIFIED_CRM_CUTOVER_ENABLED=true -e EPHARM_UNIFIED_UPSTREAM=native:8060 \
  -v "$bundle_dir/Caddyfile.http:/etc/caddy/Caddyfile:ro" \
  caddy:2.10-alpine caddy run --config /etc/caddy/Caddyfile >/dev/null
cat > "$tmp_dir/request.py" <<'PYTHON'
from pathlib import Path
from http.client import HTTPConnection
import sys
connection=HTTPConnection('proxy',80,timeout=5)
connection.request(sys.argv[1],sys.argv[2],headers={'Host':'crm.inkar.kz',
    'Origin':'https://crm.inkar.kz','X-Forwarded-For':'203.0.113.199','X-Pharmapay-Key':'must-not-forward',
    'X-Staff-Session':'synthetic-limited-session'})
response=connection.getresponse()
Path('/app/headers').write_text('HTTP/1.1 '+str(response.status)+' '+response.reason+'\n'+
    '\n'.join(k+': '+v for k,v in response.getheaders()))
Path('/app/body').write_bytes(response.read())
connection.close()
PYTHON
request() {
 docker exec "$stub" python /app/request.py "$1" "$2"
}
for _ in $(seq 1 25); do if request GET /; then break; fi; sleep 0.2; done
assert_native() {
 local client_ip
 client_ip="$(docker inspect "$stub" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')"
 python3 - "$tmp_dir" "$1" "$client_ip" <<'PYTHON'
from pathlib import Path
import json,sys
root=Path(sys.argv[1]); headers=(root/'headers').read_text().lower()
assert headers.startswith('http/1.1 200 '),headers
assert 'cache-control: no-store' in headers and 'referrer-policy: no-referrer' in headers, headers
value=json.loads((root/'body').read_text())
assert value.pop('forwarded')==sys.argv[3], value
assert value==dict(path=sys.argv[2],host='epharm.inkar.kz',origin='https://crm.inkar.kz',proto='https',key=None,staff='synthetic-limited-session'),value
PYTHON
}
assert_native /merch/portal
for path in /producer /producer/login '/staff?access=synthetic-access' /tasks/staff /assets/synthetic.js; do
 request GET "$path"; assert_native "$path"
done
for path in /api/staff/auth/code /api/staff/visits /api/producer/auth/sms/request /api/public/merch/tasks/items/synthetic/submit; do
 request POST "$path"; assert_native "$path"
done
for path in /api/login /api/admin/merch/tasks /api/posm/heartbeat /api/public/task-dispatch/submit /api/placement-files/old; do
 request POST "$path"; grep -q '^HTTP/1.1 409 ' "$tmp_dir/headers"
 python3 - "$tmp_dir/body" <<'PYTHON'
import json,sys
assert json.load(open(sys.argv[1]))['code']=='MERCH_PORTAL_MOVED'
PYTHON
done
request GET /admin; grep -q '^Location: https://epharm.inkar.kz/login' "$tmp_dir/headers"
request GET /learn/merch/claim; grep -q '^Location: https://epharm.inkar.kz/learn/merch/claim' "$tmp_dir/headers"
request POST /staff; grep -q '^HTTP/1.1 404 ' "$tmp_dir/headers"
request GET /unknown; grep -q '^HTTP/1.1 404 ' "$tmp_dir/headers"
# Default-off config retains exactly the original upstream and shop route.
for flag in false true; do
 docker run --rm --network none -e SITE_DOMAIN=shop.example.test -e SERVER_IP=10.10.1.80 \
   -e EPHARM_UNIFIED_CRM_CUTOVER_ENABLED="$flag" \
   -v "$bundle_dir/Caddyfile.http:/etc/caddy/Caddyfile:ro" \
   caddy:2.10-alpine caddy adapt --config /etc/caddy/Caddyfile > "$tmp_dir/adapt-$flag.json"
done
python3 - "$tmp_dir" <<'PYTHON'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
for flag in ('false','true'):
 raw=(root/('adapt-'+flag+'.json')).read_text(); value=json.loads(raw)
 assert ('\"'+flag+'\" == \"true\"') in str(value)
 assert '10.10.1.80:8080' in raw and 'router:8080' in raw
 assert '10.10.1.76:8060' in raw
PYTHON
printf 'CRM unified edge: native scoped transport, compatibility, retired writers and default-off preserved\n'
