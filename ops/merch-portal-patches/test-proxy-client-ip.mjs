import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Consume the real `caddy adapt` output; never infer trusted clients from arbitrary XFF.
const config = JSON.parse(readFileSync(0, 'utf8'));
const compose = readFileSync(new URL('../../docker-compose.prod.yml', import.meta.url), 'utf8');
const template = readFileSync(new URL('../../.env.prod.example', import.meta.url), 'utf8');
const objects = (value) => value && typeof value === 'object'
  ? [value, ...Object.values(value).flatMap(objects)] : [];
function validate(value, deployment, example) {
  const servers = Object.values(value.apps.http.servers);
  assert.ok(servers.length > 0);
  for (const server of servers) {
    assert.equal(server.trusted_proxies?.source, 'static');
    assert.deepEqual([...server.trusted_proxies.ranges].sort(), ['10.10.1.74/32', '10.10.1.80/32']);
    assert.ok(server.trusted_proxies_strict === 1 || server.trusted_proxies_strict === true);
    assert.deepEqual(server.client_ip_headers, ['X-Forwarded-For']);
  }
  const proxies = objects(value).filter((node) => node.handler === 'reverse_proxy');
  const backend = proxies.filter((proxy) => proxy.upstreams?.some(({ dial }) => dial === 'backend:8080'));
  assert.ok(backend.length >= 2, 'both TLS and external-ingress API routes must set the sanitized client');
  for (const proxy of backend) {
    assert.deepEqual(proxy.headers?.request?.set?.['X-Forwarded-For'], ['{http.vars.client_ip}']);
  }
  assert.ok(proxies.some((proxy) => proxy.upstreams?.some(({ dial }) => dial === 'frontend:80')
    && proxy.headers?.request?.set?.['X-Forwarded-For']?.[0] === '{http.vars.client_ip}'),
  'the internal frontend API proxy must receive the sanitized client too');
  assert.ok(deployment.includes('SERVER_FORWARD_HEADERS_STRATEGY: ${SERVER_FORWARD_HEADERS_STRATEGY:-none}'));
  assert.ok(deployment.includes('SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: ${SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES:-(?!)}'));
  assert.match(example, /^SERVER_FORWARD_HEADERS_STRATEGY=none$/m);
  assert.match(example, /^SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=\(\?!\)$/m);
}
validate(config, compose, template);

// Guard against broad private-network trust, leftmost spoofing and unsafe default activation.
const changed = (mutate) => { const copy = structuredClone(config); mutate(copy); return copy; };
const firstServer = (value) => Object.values(value.apps.http.servers)[0];
assert.throws(() => validate(changed((value) => {
  firstServer(value).trusted_proxies.ranges = ['10.0.0.0/8'];
}), compose, template));
assert.throws(() => validate(changed((value) => {
  firstServer(value).trusted_proxies_strict = false;
}), compose, template));
assert.throws(() => validate(changed((value) => {
  const proxy = objects(value).find((node) => node.handler === 'reverse_proxy'
    && node.upstreams?.some(({ dial }) => dial === 'backend:8080'));
  proxy.headers.request.set['X-Forwarded-For'] = ['{http.request.header.X-Forwarded-For}'];
}), compose, template));
assert.throws(() => validate(config, compose.replace('HEADERS_STRATEGY:-none', 'HEADERS_STRATEGY:-native'), template));
assert.throws(() => validate(config, compose.replace('INTERNAL_PROXIES:-(?!)', 'INTERNAL_PROXIES:-.*'), template));
assert.throws(() => validate(config, compose, template.replace('HEADERS_STRATEGY=none', 'HEADERS_STRATEGY=framework')));
console.log('Trusted proxy contract: exact ingress peers, strict XFF sanitization, deny-by-default Tomcat settings; six negative cases passed.');
