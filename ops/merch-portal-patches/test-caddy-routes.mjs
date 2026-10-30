import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// Feed this file the JSON produced by `caddy adapt --config Caddyfile`.
const config = JSON.parse(readFileSync(0, "utf8"));
const servers = Object.values(config.apps.http.servers);
const cutover = process.argv[2] ?? 'false';
assert.ok(['true', 'false'].includes(cutover));
const staffAccess = process.argv[3] ?? 'false';
assert.ok(['true', 'false'].includes(staffAccess));
const flatten = (routes) => routes.flatMap((route) => [
  ...route.handle ?? [],
  ...flatten((route.handle ?? []).flatMap((handler) => handler.routes ?? [])),
]);
let checked = 0;

for (const server of servers) {
  const hostRoutes = server.routes ?? [];
  for (const hostRoute of hostRoutes) {
    const top = hostRoute.handle?.[0]?.routes ?? [];
    const merchIndex = top.findIndex((route) =>
      route.match?.some((match) => match.path?.includes("/merch/*")));
    if (merchIndex < 0) continue;

    const fallbackIndex = top.findIndex((route) => !route.match && route.group);
    assert.ok(fallbackIndex < 0 || merchIndex < fallbackIndex, "merch route must precede frontend fallback");

    const staffRoutes = top[merchIndex].handle?.[0]?.routes?.[0]?.handle?.[0]?.routes ?? [];
    const allowed = [
      ["GET", ["/merch/staff"]],
      ["GET", ["/merch/assets/*", "/merch/pharmacy-shelf.png"]],
      ["POST", [
        "/merch/api/public/task-dispatch",
        "/merch/api/public/task-dispatch/queue",
        "/merch/api/public/task-dispatch/start",
        "/merch/api/public/task-dispatch/checklist",
        "/merch/api/public/task-dispatch/evidence",
        "/merch/api/public/task-dispatch/submit",
      ]],
      ["GET", [
        "/merch/api/task-files/*",
        "/merch/api/request-item-files/*",
        "/merch/api/placement-files/*",
      ]],
    ];

    assert.equal(staffRoutes.length, allowed.length + 3, "explicit role landing and retired-API fence; no extra public merch routes");
    assert.deepEqual(staffRoutes[0].match, [{ method: ['GET', 'HEAD'], path: ['/merch/portal'] }]);
    const landing = flatten([staffRoutes[0]]).find(({ handler }) => handler === 'reverse_proxy');
    assert.deepEqual(landing?.upstreams, [{ dial: 'frontend:80' }]);
    assert.deepEqual(landing?.headers?.request?.delete, ['X-Pharmapay-Key', 'X-Staff-Session']);
    const compatibility = staffRoutes.slice(1);
    assert.equal(compatibility[0].match[0].expression.expr, `"${cutover}" == "true"`);
    const retired = flatten([compatibility[0]]);
    assert.ok(!retired.some(({ handler }) => handler === 'reverse_proxy'), 'retired API must never reach old CRM');
    assert.equal(retired.find(({ status_code }) => status_code)?.status_code, 409);
    for (const [index, [method, paths]] of allowed.entries()) {
      assert.deepEqual(compatibility[index + 1].match, [{ method: [method], path: paths }]);
      const handlers = compatibility[index + 1].handle?.[0]?.routes?.[0]?.handle ?? [];
      if (index === 0) {
        const routes = compatibility[index + 1].handle[0].routes;
        assert.equal(routes.length, 3, 'task-only compatibility, retired staff entry and legacy default');
        assert.deepEqual(routes[0].match[0].query, { task: ['*'] });
        assert.equal(routes[0].match[0].expression.expr, `"${cutover}" == "true"`);
        const native = flatten([routes[0]]);
        assert.equal(native.find(({ handler }) => handler === 'rewrite')?.uri, '/tasks/staff?');
        const proxy = native.find(({ handler }) => handler === 'reverse_proxy');
        assert.deepEqual(proxy?.upstreams, [{ dial: 'frontend:80' }]);
        assert.deepEqual(proxy?.headers?.request?.delete, ['X-Pharmapay-Key', 'X-Staff-Session']);
        assert.equal(routes[1].match[0].expression.expr, `"${cutover}" == "true"`);
        assert.equal(routes[1].match[0].query, undefined, 'retired entry also handles plain/access when shared codes are off');
        const retiredEntry = flatten([routes[1]]);
        assert.equal(retiredEntry.find(({ handler }) => handler === 'rewrite')?.uri, '/staff?');
        const retiredProxy = retiredEntry.find(({ handler }) => handler === 'reverse_proxy');
        assert.deepEqual(retiredProxy?.upstreams, [{ dial: 'frontend:80' }]);
        assert.deepEqual(retiredProxy?.headers?.request?.delete, ['X-Pharmapay-Key', 'X-Staff-Session']);
        const fallback = flatten([routes[2]]);
        assert.equal(fallback.find(({ status_code }) => status_code)?.status_code, 302);
        assert.deepEqual(fallback.find(({ headers }) => headers?.Location)?.headers?.Location,
          ["https://crm.inkar.kz/staff{http.request.uri.prefixed_query}"]);
        continue;
      }
      assert.equal(handlers[0]?.strip_path_prefix, "/merch");
      assert.equal(handlers[1]?.handler, "reverse_proxy");
      assert.deepEqual(handlers[1]?.headers?.request?.delete, ["X-Pharmapay-Key"]);
      assert.ok(handlers[1]?.transport?.tls, "merch upstream must use verified TLS");
    }
    assert.equal(staffRoutes.at(-1)?.handle?.[0]?.handler, "static_response");
    assert.equal(staffRoutes.at(-1)?.handle?.[0]?.status_code, 404);
    checked++;
  }
}

assert.equal(checked, 2, "both public TLS and INKAR ingress routes must protect merchandising");
console.log(`Merch Caddy routes: cutover=${cutover}, explicit SPA entry, CRM default, retired API fence and default deny.`);
