import http from 'k6/http'
import { check } from 'k6'
import exec from 'k6/execution'
import { SharedArray } from 'k6/data'
import { Counter } from 'k6/metrics'

if (__ENV.ISOLATED_RPS_LOAD_TEST !== 'DISPOSABLE_DATABASE_NO_SMS') throw new Error('Isolated acknowledgement required')
const input = JSON.parse(open('/fixture/learner.jwt.private.json'))
if (input.kind !== 'epharm-isolated-learner-jwt-v1' || !/^pr164_[a-f0-9]{10}$/.test(input.runId) || input.expectedDatabase !== `epharm_rps_load_${input.runId}` || input.accounts.length !== 500) throw new Error('Synthetic learner fixture required')
if (new Set(input.accounts.map(x => x.id)).size !== 500 || new Set(input.accounts.map(x => x.token)).size !== 500) throw new Error('Distinct500 normal JWT sessions required')
if (!input.accounts.every(x => /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(x.token) && Number.isInteger(x.expectedAssignments) && x.expectedAssignments >= 0)) throw new Error('Invalid learner session')
const accounts = new SharedArray('normal synthetic learner JWTs', () => input.accounts)
const completed = new Counter('learner_completed')
const errors = new Counter('learner_errors')
const rateLimited = new Counter('learner_rate_limited')
const transport = new Counter('learner_transport')
const upstream = new Counter('learner_5xx')
const unexpected = new Counter('learner_unexpected_status')

export const options = {
  maxRedirects: 0,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  scenarios: { learners: { executor: 'constant-arrival-rate', rate: 500, timeUnit: '30s', duration: '2m', preAllocatedVUs: 25, maxVUs: 50 } },
  thresholds: {
    checks: ['rate==1'], http_req_failed: ['rate==0'], dropped_iterations: ['count==0'],
    learner_completed: ['count>=2000'], learner_errors: ['count==0'], learner_rate_limited: ['count==0'],
    learner_transport: ['count==0'], learner_5xx: ['count==0'], learner_unexpected_status: ['count==0'],
    'http_req_duration{workload:learner-jwt}': ['p(95)<2000', 'p(99)<5000'],
  },
}

export function setup() {
  const response = http.get('http://127.0.0.1:8080/api/health', { redirects: 0, timeout: '15s' })
  if (response.status !== 200 || response.json()?.status !== 'ok') throw new Error('Owned backend not healthy')
  for (const metric of [completed, errors, rateLimited, transport, upstream, unexpected]) metric.add(0)
}

export default function () {
  const account = accounts[exec.scenario.iterationInTest % accounts.length]
  const response = http.get('http://127.0.0.1:8080/api/mobile/training', {
    headers: { Authorization: `Bearer ${account.token}` }, redirects: 0, timeout: '15s',
    tags: { workload: 'learner-jwt' }, responseCallback: http.expectedStatuses(200),
  })
  if (response.status === 0) transport.add(1)
  if (response.status === 429) rateLimited.add(1)
  if (response.status >= 500) upstream.add(1)
  if (response.status !== 200) unexpected.add(1)
  let body
  try { body = response.json() } catch { body = null }
  const ok = check(response, { 'learner JWT gets expected owned overview': r => r.status === 200 && body?.total === account.expectedAssignments && Array.isArray(body?.assignments) && body.assignments.length === account.expectedAssignments && Array.isArray(body?.certificates) && Array.isArray(body?.notifications) })
  if (ok) completed.add(1)
  else errors.add(1)
}

export function handleSummary(data) {
  // setup_data and tokens must never enter uploaded reports.
  return { '/results/learner-jwt-summary.json': JSON.stringify({ outcome: 'completed', kind: 'epharm-isolated-learner-jwt-summary-v1', accounts: 500, route: '/api/mobile/training', normalApiIssuedTokens: true, metrics: data.metrics, testRunDurationMs: data.state?.testRunDurationMs }, null, 2) }
}
