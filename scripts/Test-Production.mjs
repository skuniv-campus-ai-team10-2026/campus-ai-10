// Bounded local HTTPS/security/concurrency verification. Uses synthetic data only.
import https from 'node:https';
import http from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const base = new URL(process.env.VERIFY_ORIGIN ?? 'https://127.0.0.1:4174');
if (!['https:', 'http:'].includes(base.protocol) || !['127.0.0.1', 'localhost'].includes(base.hostname)
    || base.username || base.password || base.pathname !== '/') throw new Error('Use a loopback HTTP(S) origin only.');
const usesTls = base.protocol === 'https:';
if (process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0') throw new Error('TLS certificate verification must remain enabled.');
if (process.env.SKOPE_VERIFICATION_WRITES !== '1') throw new Error('Run the dedicated Start-VerificationBackend.ps1, then set SKOPE_VERIFICATION_WRITES=1.');
const ca = usesTls ? await readFile(path.join(root, '.runtime/tls/ca-cert.pem')) : null;
const secrets = JSON.parse(await readFile(path.join(root, '.runtime/local-secrets.json'), 'utf8'));
const transport = usesTls ? https : http;
const agent = new transport.Agent({ ...(usesTls ? { ca, rejectUnauthorized: true } : {}), keepAlive: true, maxSockets: 40 });
const runId = randomUUID().slice(0, 8);
const checks = [], stages = [];
const report = { startedAt: new Date().toISOString(), origin: base.origin, database: 'skope_verification',
  aiMode: 'DEMO', embeddingMode: 'RULE_ONLY', syntheticData: true, liveProviderCalls: 0, runId, checks, stages };

function request(session, method, route, body, extra = {}) {
  const payload = typeof body === 'string' ? body : body == null ? null : JSON.stringify(body);
  const headers = { ...(payload == null ? {} : { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }),
    ...(session.cookie ? { Cookie: session.cookie } : {}),
    ...(session.token && method !== 'GET' && method !== 'HEAD' ? { [session.header]: session.token } : {}), ...extra };
  return new Promise((resolve, reject) => {
    const started = performance.now();
    const req = transport.request({ hostname: base.hostname, port: base.port, method, path: route, headers, agent, timeout: 15000 }, res => {
      const chunks = []; let bytes = 0;
      res.on('data', chunk => { bytes += chunk.length; if (bytes > 2 * 1024 * 1024) res.destroy(new Error('Response limit')); else chunks.push(chunk); });
      res.on('error', reject);
      res.on('end', () => {
        for (const cookie of res.headers['set-cookie'] ?? []) if (cookie.startsWith('JSESSIONID=')) session.cookie = cookie.split(';', 1)[0];
        const text = Buffer.concat(chunks).toString('utf8'); let json = null;
        try { json = JSON.parse(text); } catch { /* Static HTML is expected for SPA checks. */ }
        resolve({ status: res.statusCode, headers: res.headers, text, json, ms: performance.now() - started,
          authorizedTls: res.socket?.authorized ?? req.socket?.authorized });
      });
    });
    req.on('timeout', () => req.destroy(new Error('Request timeout')));
    req.on('error', reject); req.end(payload);
  });
}
function expect(r, status) { assert.equal(r.status, status, `Expected HTTP ${status}, got ${r.status}`); return r.json; }
async function csrf(s) { const r = expect(await request(s, 'GET', '/api/auth/csrf'), 200); s.token = r.token; s.header = r.headerName; }
async function login(username) {
  const s = {}; await csrf(s); const before = s.cookie;
  const response = await request(s, 'POST', '/api/auth/login', { username, password: secrets.demoPassword });
  s.user = expect(response, 200); s.rotated = before !== s.cookie; s.loginCookies = response.headers['set-cookie'] ?? [];
  await csrf(s); return s;
}
async function check(name, action) {
  try { await action(); checks.push({ name, status: 'PASS' }); }
  catch (error) { checks.push({ name, status: 'FAIL', reason: error.message }); }
}
function payload(suffix) { return { clientRequestId: randomUUID(), originalText: `[합성 검증 ${runId}] 검증관 ${suffix}호 와이파이가 끊겨요.`,
  category: 'IT', subcategory: 'WIFI', building: `검증${runId}${suffix}관`, floor: '5',
  locationText: `검증${runId}${suffix}관 502호`, symptom: '합성 검증용 무선망 끊김' }; }
function summary(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const q = p => Math.round(sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)] * 100) / 100;
  return { count: sorted.length, minMs: q(0), p50Ms: q(.5), p95Ms: q(.95), p99Ms: q(.99), maxMs: q(1) };
}

try {
  const metaResponse = await request({}, 'GET', '/api/meta');
  const meta = expect(metaResponse, 200);
  assert.equal(meta.databaseMode, 'MYSQL'); assert.equal(meta.aiMode, 'DEMO'); assert.equal(meta.embeddingMode, 'RULE_ONLY');
  if (usesTls) {
  await check('TLS validates using only the workspace CA; no global trust changes', async () => assert.equal(metaResponse.authorizedTls, true));
  await check('Untrusted TLS is rejected', async () => {
    await assert.rejects(new Promise((resolve, reject) => {
      const r = https.get(base, { agent: false, rejectUnauthorized: true }, response => { response.resume(); resolve(); });
      r.on('error', reject);
    }), error => ['DEPTH_ZERO_SELF_SIGNED_CERT', 'SELF_SIGNED_CERT_IN_CHAIN', 'UNABLE_TO_VERIFY_LEAF_SIGNATURE'].includes(error.code));
  });
  } else {
    checks.push({ name: 'TLS connection and trust validation', status: 'SKIPPED', reason: 'HTTP-only run; does not verify HTTPS or browser Secure-cookie transport.' });
  }
  await check('Production SPA deep links and asset content types', async () => {
    for (const route of ['/', '/reports/1', '/admin/incidents/1']) {
      const r = await request({}, 'GET', route); expect(r, 200); assert.match(r.headers['content-type'], /text\/html/); assert.match(r.text, /<div id="root">/);
    }
    const html = (await request({}, 'GET', '/')).text;
    const asset = html.match(/src="([^\"]+\.js)"/)?.[1]; assert.ok(asset?.startsWith('/assets/'));
    const r = await request({}, 'GET', asset); expect(r, 200); assert.match(r.headers['content-type'], /javascript/);
    const css = html.match(/href="([^\"]+\.css)"/)?.[1]; assert.ok(css?.startsWith('/assets/'));
    assert.match((await request({}, 'GET', css)).headers['content-type'], /text\/css/);
    const head = await request({}, 'HEAD', '/'); expect(head, 200); assert.equal(head.text, '');
    expect(await request({}, 'POST', '/', {}), 405);
  });
  await check('Security headers on static and API responses', async () => {
    for (const route of ['/', '/api/meta']) {
      const r = await request({}, 'GET', route); assert.equal(r.headers['x-content-type-options'], 'nosniff');
      assert.equal(r.headers['x-frame-options'], 'DENY'); assert.match(r.headers['content-security-policy'], /frame-ancestors 'none'/);
      assert.match(r.headers['cache-control'], /no-store/);
    }
  });
  await check('No directory traversal or runtime credential files', async () => {
    for (const route of ['/../.runtime/local-secrets.json', '/%2e%2e/.runtime/local-secrets.json', '/%252e%252e/README.md', '/assets/..%5c..%5c.runtime/local-secrets.json', '/.env']) {
      const r = await request({}, 'GET', route); assert.ok([400, 403, 404].includes(r.status)); assert.ok(!r.text.includes(secrets.demoPassword));
    }
    expect(await request({}, 'GET', '/assets/missing.js'), 404);
  });
  await check('Unexpected Host and cross-origin requests are rejected', async () => {
    expect(await request({}, 'GET', '/', null, { Host: 'untrusted.invalid' }), 403);
    expect(await request({}, 'POST', '/api/auth/login', {}, { Origin: 'https://untrusted.invalid' }), 403);
  });
  await check('Oversized body and unauthenticated/CSRF access are rejected', async () => {
    expect(await request({}, 'POST', '/api/auth/login', 'x'.repeat(262145)), 413);
    expect(await request({}, 'GET', '/api/admin/reports'), 401);
    expect(await request({}, 'POST', '/api/auth/login', { username: 'student1', password: 'invalid' }), 403);
  });
  const student = await login('student1'), other = await login('student2'), admin = await login('admin');
  await check('Session ID rotates; cookie is Secure, HttpOnly and SameSite=Lax', async () => {
    assert.ok(student.rotated); const cookie = student.loginCookies.join(';');
    assert.match(cookie, /;\s*Secure/i); assert.match(cookie, /;\s*HttpOnly/i); assert.match(cookie, /SameSite=Lax/i);
  });
  await check('SQL injection does not bypass login', async () => {
    const s = {}; await csrf(s); expect(await request(s, 'POST', '/api/auth/login', { username: "' OR 1=1 --", password: 'invalid' }), 401);
  });
  const item = expect(await request(student, 'POST', '/api/reports', payload('security')), 201);
  await check('Student ownership and role access are enforced', async () => {
    expect(await request(other, 'GET', `/api/reports/${item.id}`), 404);
    expect(await request(student, 'GET', '/api/admin/reports'), 403);
  });
  await check('Stored content is returned as JSON and does not alter permissions', async () => {
    const p = { ...payload('xss'), originalText: '<img src=x onerror=alert(1)> 합성 보안 검증', studentId: other.user.id, status: 'RESOLVED', assignedDepartmentId: 999999 };
    const r = expect(await request(student, 'POST', '/api/reports', p), 201);
    assert.equal(r.studentId, student.user.id); assert.equal(r.status, 'RECEIVED'); assert.notEqual(r.assignedDepartmentId, 999999);
    const response = await request(student, 'GET', `/api/reports/${r.id}`); expect(response, 200);
    assert.match(response.headers['content-type'], /application\/json/); assert.equal(response.json.originalText, p.originalText);
  });
  await check('Repeated simultaneous UUID has one saved winner', async () => {
    const p = payload('duplicate'); const sessions = await Promise.all(Array.from({ length: 8 }, () => login('student1')));
    const rs = await Promise.all(sessions.map(s => request(s, 'POST', '/api/reports', p)));
    assert.equal(rs.filter(r => r.status === 201).length, 1); assert.equal(rs.filter(r => r.status === 409).length, 7);
  });
  await check('Concurrent status changes have one winner and one audit transition', async () => {
    const a = await login('admin'), b = await login('admin');
    const rs = await Promise.all([a, b].map(s => request(s, 'PATCH', `/api/admin/reports/${item.id}/status`, { status: 'IN_PROGRESS' })));
    assert.deepEqual(rs.map(r => r.status).sort(), [200, 409]);
    const done = expect(await request(admin, 'PATCH', `/api/admin/reports/${item.id}/status`, { status: 'RESOLVED', resolutionText: '합성 검증 완료' }), 200);
    assert.equal(done.events.filter(e => e.eventType === 'STATUS_CHANGED').length, 2);
    assert.equal(expect(await request(admin, 'GET', `/api/admin/incidents/${done.incidentId}`), 200).status, 'RESOLVED');
  });
  await check('Logout invalidates the existing authenticated session', async () => {
    const s = await login('student2'); expect(await request(s, 'POST', '/api/auth/logout', {}), 200);
    expect(await request(s, 'GET', '/api/auth/me'), 401);
  });
  // Closed-loop tests measure local pipeline latency, not production capacity or independent-user scale.
  for (const concurrency of [4, 12, 24]) {
    const sessions = await Promise.all(Array.from({ length: concurrency }, (_, i) => login(i % 2 ? 'student1' : 'student2')));
    const reads = [], writes = [], failures = [], ids = [];
    const start = performance.now();
    await Promise.all(sessions.map(async (s, u) => {
      for (let iteration = 0; iteration < 5; iteration++) {
        try {
          const created = await request(s, 'POST', '/api/reports', payload(`${concurrency}-${u}-${iteration}`));
          expect(created, 201); writes.push(created.ms); ids.push(created.json.id);
          const detail = await request(s, 'GET', `/api/reports/${created.json.id}`); expect(detail, 200);
          assert.equal(detail.json.studentId, s.user.id); reads.push(detail.ms);
        } catch (error) { failures.push({ session: u, iteration, error: error.message }); }
      }
    }));
    const elapsedMs = performance.now() - start;
    stages.push({ concurrentSessions: concurrency, distinctStudentAccounts: 2, iterationsPerSession: 5,
      elapsedMs: Math.round(elapsedMs), requestsPerSecond: Math.round((reads.length + writes.length) / elapsedMs * 100000) / 100,
      reads: summary(reads), writes: summary(writes), uniqueCreatedIds: new Set(ids).size,
      failures, status: failures.length === 0 && reads.length === concurrency * 5 && new Set(ids).size === concurrency * 5 ? 'PASS' : 'FAIL' });
  }
} catch (error) { checks.push({ name: 'Verification execution', status: 'FAIL', reason: error.message }); }
finally {
  agent.destroy(); report.completedAt = new Date().toISOString();
  report.status = checks.some(x => x.status === 'FAIL') || stages.length !== 3 || stages.some(x => x.status !== 'PASS') ? 'FAIL' : usesTls ? 'PASS' : 'PASS_HTTP_ONLY';
  report.limits = ['Local closed-loop requests, 2 student accounts, max 24 concurrent sessions.',
    'No LIVE AI latency/cost or internet/mobile network latency included.', 'Synthetic DB is retained; no real reports were sent.',
    usesTls ? 'Workspace CA is trusted only by this test client; no public CA/domain/deployment verified.' : 'HTTP only; cookies are manually forwarded by the test client, not a browser. Secure-cookie transport and HTTPS are unverified.',
    'This is not a penetration test or production capacity guarantee.'];
  await mkdir(path.join(root, 'docs/evaluation'), { recursive: true });
  await writeFile(path.join(root, `docs/evaluation/production-${usesTls ? 'https' : 'http'}-results.json`), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ status: report.status, checks: checks.map(({ name, status, reason }) => ({ name, status, reason })), stages }, null, 2));
  if (report.status === 'FAIL') process.exitCode = 1;
}
