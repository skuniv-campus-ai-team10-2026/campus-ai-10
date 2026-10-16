#!/usr/bin/env node
// Local production-bundle preview only. This does not publish the app or manage certificates.
import http from 'node:http';
import https from 'node:https';
import { createReadStream } from 'node:fs';
import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const usage = `Local SKOPE production preview (loopback only)
  node scripts/Preview-Production.mjs [--port 4173] [--backend-port 8080]
  node scripts/Preview-Production.mjs --port 4174 --cert PATH --key PATH

Build frontend/dist first. Start the backend separately.
Optional TLS files must already exist; no certificate creation or trust-store changes occur.
For HTTPS session-cookie validation, start the backend with COOKIE_SECURE=true.
`;
const args = process.argv.slice(2);
if (args.includes('--help')) { console.log(usage); process.exit(0); }
const options = { port: '4173', 'backend-port': '8080' };
for (let index = 0; index < args.length; index += 2) {
  const key = args[index]?.replace(/^--/, '');
  if (!args[index]?.startsWith('--') || !['port', 'backend-port', 'cert', 'key'].includes(key)
      || !args[index + 1] || args[index + 1].startsWith('--')) throw new Error(usage);
  options[key] = args[index + 1];
}
function port(value) {
  const result = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(result) || result < 1 || result > 65535)
    throw new Error('Port must be an integer from 1 to 65535.');
  return result;
}
const listenPort = port(options.port);
const backendPort = port(options['backend-port']);
if (listenPort === backendPort) throw new Error('Preview and backend ports must differ.');
if (Boolean(options.cert) !== Boolean(options.key)) throw new Error('Supply both --cert and --key.');
const tls = Boolean(options.cert);
const scheme = tls ? 'https' : 'http';
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const distRoot = await realpath(path.join(projectRoot, 'frontend', 'dist'));
if (!(await stat(path.join(distRoot, 'index.html'))).isFile()) throw new Error('Build frontend/dist first.');
const hosts = new Set([`127.0.0.1:${listenPort}`, `localhost:${listenPort}`]);
// Browsers omit the default port in Host/Origin.
if ((!tls && listenPort === 80) || (tls && listenPort === 443)) {
  for (const host of ['127.0.0.1', 'localhost']) hosts.add(host);
}
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8',
};
const hopHeaders = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade']);
const maxBody = 256 * 1024;
const knownSpaRoute = /^\/(?:report\/(?:analyze|confirm)|reports(?:\/(?:success|\d+))?|admin\/(?:reports(?:\/\d+)?|repeated|incidents\/\d+))?\/?$/;

function stripHopHeaders(headers) {
  const omit = new Set(hopHeaders);
  for (const item of String(headers.connection ?? '').split(',')) omit.add(item.trim().toLowerCase());
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !omit.has(name.toLowerCase())));
}
function sendError(res, status, code, message) {
  if (res.headersSent) { res.destroy(); return; }
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify({ code, message, fieldErrors: {} }));
}
function contained(filename) {
  const relative = path.relative(distRoot, filename);
  return relative === '' || (!path.isAbsolute(relative) && relative !== '..' && !relative.startsWith(`..${path.sep}`));
}
function parsePath(rawUrl) {
  if (!rawUrl?.startsWith('/') || rawUrl.startsWith('//')) throw new Error('Invalid request target.');
  const rawPath = rawUrl.split('?', 1)[0];
  const pathname = decodeURIComponent(rawPath);
  // Reject traversal before normalization, Windows ADS/backslashes, control bytes and double encoding.
  if (/[\\:\x00-\x1f\x7f#]/.test(pathname) || /%[0-9a-f]{2}/i.test(pathname)
      || pathname.split('/').some(segment => segment.startsWith('.')))
    throw new Error('Disallowed request path.');
  return pathname;
}
function proxy(req, res) {
  if (!['GET', 'HEAD', 'POST', 'PATCH', 'PUT', 'DELETE', 'OPTIONS'].includes(req.method)) {
    res.setHeader('Allow', 'GET, HEAD, POST, PATCH, PUT, DELETE, OPTIONS');
    sendError(res, 405, 'METHOD_NOT_ALLOWED', 'Method is not supported.'); return;
  }
  if (req.headers.origin && req.headers.origin !== `${scheme}://${req.headers.host?.toLowerCase()}`) {
    sendError(res, 403, 'ORIGIN_REJECTED', 'Use the same preview origin for API requests.'); return;
  }
  if (Number(req.headers['content-length'] ?? 0) > maxBody) {
    sendError(res, 413, 'REQUEST_TOO_LARGE', 'Request exceeds the local preview body limit.'); return;
  }
  const headers = stripHopHeaders(req.headers);
  delete headers.forwarded;
  for (const key of Object.keys(headers)) if (key.startsWith('x-forwarded-')) delete headers[key];
  headers.host = `127.0.0.1:${backendPort}`;
  headers['x-forwarded-host'] = req.headers.host;
  headers['x-forwarded-proto'] = scheme;
  headers['x-forwarded-for'] = '127.0.0.1';
  const upstream = http.request({ hostname: '127.0.0.1', port: backendPort, method: req.method,
    path: req.url, headers, timeout: 60000 }, incoming => {
    if (res.destroyed || res.writableEnded) { incoming.destroy(); return; }
    res.writeHead(incoming.statusCode ?? 502, { ...stripHopHeaders(incoming.headers), 'cache-control': 'no-store' });
    incoming.on('error', () => res.destroy());
    incoming.pipe(res);
  });
  upstream.on('timeout', () => {
    sendError(res, 504, 'UPSTREAM_TIMEOUT', 'Backend request timed out.'); upstream.destroy();
  });
  upstream.on('error', () => {
    if (!res.writableEnded && !res.destroyed) sendError(res, 502, 'UPSTREAM_UNAVAILABLE', 'Start the local backend before using the API.');
  });
  let received = 0;
  req.on('data', chunk => {
    received += chunk.length;
    if (received > maxBody && !res.writableEnded) {
      req.unpipe(upstream); upstream.destroy();
      sendError(res, 413, 'REQUEST_TOO_LARGE', 'Request exceeds the local preview body limit.');
    }
  });
  req.on('aborted', () => upstream.destroy());
  res.on('close', () => { if (!res.writableEnded) upstream.destroy(); });
  req.pipe(upstream);
}
async function serveStatic(req, res, pathname) {
  if (!['GET', 'HEAD'].includes(req.method)) {
    res.setHeader('Allow', 'GET, HEAD');
    sendError(res, 405, 'METHOD_NOT_ALLOWED', 'Static resources support GET and HEAD.'); return;
  }
  let filename = path.resolve(distRoot, `.${pathname}`);
  if (!contained(filename)) { sendError(res, 403, 'PATH_REJECTED', 'Path is outside the frontend build.'); return; }
  try {
    if (!(await stat(filename)).isFile()) throw new Error('Not a file.');
  } catch {
    if (!knownSpaRoute.test(pathname)) { sendError(res, 404, 'ASSET_NOT_FOUND', 'Resource was not found.'); return; }
    filename = path.join(distRoot, 'index.html');
  }
  filename = await realpath(filename);
  if (!contained(filename)) { sendError(res, 403, 'PATH_REJECTED', 'Symlink target is outside the frontend build.'); return; }
  const info = await stat(filename);
  const extension = path.extname(filename).toLowerCase();
  res.writeHead(200, { 'Content-Type': types[extension] ?? 'application/octet-stream',
    'Content-Length': info.size,
    'Cache-Control': extension === '.html' ? 'no-store' : 'public, max-age=3600' });
  if (req.method === 'HEAD') { res.end(); return; }
  const stream = createReadStream(filename);
  stream.on('error', () => res.destroy());
  res.on('close', () => stream.destroy());
  stream.pipe(res);
}
async function handler(req, res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  if (!hosts.has(req.headers.host?.toLowerCase())) {
    sendError(res, 403, 'HOST_REJECTED', 'Only the loopback preview host is allowed.'); return;
  }
  let pathname;
  try { pathname = parsePath(req.url); }
  catch { sendError(res, 400, 'PATH_REJECTED', 'Invalid or disallowed request path.'); return; }
  if (pathname === '/api' || pathname.startsWith('/api/')) { proxy(req, res); return; }
  try { await serveStatic(req, res, pathname); }
  catch { if (!res.writableEnded) sendError(res, 500, 'STATIC_READ_ERROR', 'Unable to read the frontend build.'); }
}
const server = tls
  ? https.createServer({ cert: await readFile(path.resolve(options.cert)), key: await readFile(path.resolve(options.key)), minVersion: 'TLSv1.2' }, handler)
  : http.createServer(handler);
server.requestTimeout = 60000;
server.headersTimeout = 15000;
server.keepAliveTimeout = 5000;
server.maxHeadersCount = 100;
server.on('error', error => { console.error(`Preview startup failed: ${error.code ?? error.name}`); process.exitCode = 1; });
server.listen(listenPort, '127.0.0.1', () => {
  console.log(`SKOPE local production preview: ${scheme}://127.0.0.1:${listenPort}`);
  console.log(`Static build: frontend/dist | API: http://127.0.0.1:${backendPort}`);
  console.log(tls ? 'TLS uses the supplied files. Backend COOKIE_SECURE=true is required to verify Secure cookies.'
    : 'HTTP preview only. Secure-cookie and trusted-HTTPS deployment remain separate checks.');
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  server.close(() => process.exit(0));
  server.closeIdleConnections();
  setTimeout(() => process.exit(0), 5000).unref();
});
