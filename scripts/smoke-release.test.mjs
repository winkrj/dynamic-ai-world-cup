import test from 'node:test';
import assert from 'node:assert/strict';
import { runReleaseSmoke } from './smoke-release.mjs';

const html = '<!doctype html><html><head><script type="module" src="/assets/index-abc.js"></script>'
  + '<link rel="stylesheet" href="/assets/index-def.css"></head><body><div id="root"></div></body></html>';
const health = JSON.stringify({ status: 'UP', service: 'dynamic-ai-world-cup' });
function fakeRelease({ root = html, share = root, change = () => undefined } = {}) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, ...options });
    const path = new URL(url).pathname;
    const changed = change(path, options, url);
    if (changed) return changed;
    if (path === '/' || path.startsWith('/shares/')) return new Response(path === '/' ? root : share,
      { headers: { 'content-type': 'text/html;charset=UTF-8', 'set-cookie': 'unexpected=ignore-me; Secure' } });
    if (path === '/assets/index-abc.js') return new Response('console.log("bundle");', { headers: { 'content-type': 'text/javascript' } });
    if (path === '/assets/index-def.css') return new Response('body { color: black; }', { headers: { 'content-type': 'text/css' } });
    if (path === '/api/v1/health') return new Response(health, { headers: { 'content-type': 'application/json' } });
    if (path === '/api/v1/ready') return new Response(JSON.stringify({ status: 'READY', service: 'dynamic-ai-world-cup' }),
      { headers: { 'content-type': 'application/json' } });
    if (/^\/(?:api\/v1|assets)\/release-smoke-missing-/.test(path)) return new Response('not found', { status: 404 });
    assert.fail('Unexpected network target.');
  };
  return { calls, fetchImpl };
}

test('checks HTTPS and HTTP loopback releases using credential-free GETs only', async () => {
  for (const baseUrl of ['https://release.example', 'https://release.example:8443/',
    'http://127.0.0.1:8080', 'http://localhost:8080/', 'http://[::1]:8080']) {
    const api = fakeRelease();
    assert.deepEqual(await runReleaseSmoke({ baseUrl, fetchImpl: api.fetchImpl }), {
      root: true, shareDeepLink: true, assets: 2, health: true, ready: true, apiNotFound: true, assetNotFound: true, readOnly: true,
    });
    assert.equal(api.calls.length, 8);
    for (const call of api.calls) {
      assert.equal(new URL(call.url).origin, new URL(baseUrl).origin);
      assert.equal(call.method, 'GET');
      assert.equal(call.body, undefined);
      assert.equal(call.credentials, 'omit');
      assert.equal(call.redirect, 'error');
      assert(call.signal instanceof AbortSignal);
      assert.deepEqual(Object.keys(call.headers), ['Accept']);
      assert(!/generation-jobs|\/sessions|\/api\/v1\/shares/.test(call.url));
    }
  }
});

test('rejects unsafe or ambiguous origins before fetching', async () => {
  for (const baseUrl of ['http://release.example', 'ftp://release.example', 'file:///tmp/index.html',
    'https://user:pass@release.example', 'https://release.example/path', 'https://release.example/?query=1',
    'https://release.example/#fragment', 'https://release.example?', 'https://release.example#',
    'https://release.example/../', 'https://release.example\\@evil.example',
    ' https://release.example', 'https://release.example\n', 'http://127.1:8080', null, {}]) {
    await assert.rejects(runReleaseSmoke({ baseUrl, fetchImpl: () => assert.fail('Must not fetch.') }), /exact HTTPS origin/);
  }
});

test('validates all root asset references before reading any referenced URL', async () => {
  for (const asset of ['https://evil.example/collect.js', '//evil.example/collect.js', '/api/v1/generation-jobs',
    '/assets/../api/v1/health', '/assets/%2e%2e/collect.js', '/assets/app.js?secret=1',
    '/assets/app.js#fragment', '/assets/&#47;evil.example/collect.js', '/assets/app\\evil.js']) {
    const api = fakeRelease({ root: html.replace('/assets/index-abc.js', asset) });
    await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), /same-origin \/assets\//);
    assert.equal(api.calls.length, 1);
    assert.equal(new URL(api.calls[0].url).pathname, '/');
  }
});

test('rejects a malicious deep-link asset before any asset fetch', async () => {
  const api = fakeRelease({ share: html.replace('/assets/index-def.css', '//evil.example/style.css') });
  await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), /same-origin/);
  assert.equal(api.calls.length, 2);
});

test('rejects base tags, malformed HTML, duplicate attributes and missing module bundles', async () => {
  for (const root of ['maintenance', html.replace('<head>', '<head><base href="https://evil.example/">'),
    html.replace('type="module"', 'type="text/javascript"'), html.replace('src="', 'src="/assets/other.js" src="'),
    html.replace('index-abc.js', 'index-abc.json')]) {
    const api = fakeRelease({ root });
    await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }));
    assert.equal(api.calls.length, 1);
  }
});

test('requires the same application at root and share deep link', async () => {
  const api = fakeRelease({ share: html.replace('<body>', '<body class="sensitive-response">') });
  await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), error =>
    error.message.includes('same bundled application') && !error.message.includes('sensitive-response'));
  assert.equal(api.calls.length, 2);
});

test('accepts Vite module preloads and checks a shared reference once', async () => {
  const api = fakeRelease({ root: html.replace('<head>', '<head><link rel="modulepreload" href="/assets/index-abc.js">') });
  assert.equal((await runReleaseSmoke({ fetchImpl: api.fetchImpl })).assets, 2);
  assert.equal(api.calls.filter(call => new URL(call.url).pathname === '/assets/index-abc.js').length, 1);
});

test('rejects redirects and never follows a Location header', async () => {
  const api = fakeRelease({ change: () => new Response('private body', {
    status: 302, headers: { location: 'https://evil.example/collect' },
  }) });
  await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), /HTTP 302/);
  assert.equal(api.calls.length, 1);
  for (const property of ['redirected', 'url']) {
    const response = new Response(html, { headers: { 'content-type': 'text/html' } });
    Object.defineProperty(response, property, { value: property === 'url' ? 'https://evil.example' : true });
    const followed = fakeRelease({ change: () => response });
    await assert.rejects(runReleaseSmoke({ fetchImpl: followed.fetchImpl }), /Redirected release/);
    assert.equal(followed.calls.length, 1);
  }
});

test('rejects HTML fallbacks for JavaScript, missing APIs and missing assets', async () => {
  for (const match of [path => path.endsWith('index-abc.js'), path => path.startsWith('/api/v1/release-smoke-missing-'),
    path => path.startsWith('/assets/release-smoke-missing-')]) {
    const api = fakeRelease({ change: path => match(path)
      ? new Response(html, { headers: { 'content-type': 'text/html' } }) : undefined });
    await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), /content type|expected 404/);
  }
});

test('fails safely for wrong health, malformed JSON and network errors', async () => {
  for (const body of [JSON.stringify({ status: 'DOWN', service: 'dynamic-ai-world-cup' }),
    JSON.stringify({ status: 'UP', service: 'other-service' }), 'sensitive provider response']) {
    const api = fakeRelease({ change: path => path === '/api/v1/health'
      ? new Response(body, { headers: { 'content-type': 'application/json' } }) : undefined });
    await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), error => {
      assert(!error.message.includes('sensitive provider response'));
      return true;
    });
  }
  await assert.rejects(runReleaseSmoke({ fetchImpl: async () => { throw new Error('private network detail'); } }),
    error => error.message.includes('check reachability') && !error.message.includes('private network detail'));
});

test('bounds response bytes and the number of referenced assets', async () => {
  const huge = fakeRelease({ root: `${html}${' '.repeat(256 * 1024)}` });
  await assert.rejects(runReleaseSmoke({ fetchImpl: huge.fetchImpl }), /size limit/);
  const links = Array.from({ length: 64 }, (_, index) => `<link rel="stylesheet" href="/assets/style-${index}.css">`).join('');
  const many = fakeRelease({ root: html.replace('<head>', `<head>${links}`) });
  await assert.rejects(runReleaseSmoke({ fetchImpl: many.fetchImpl }), /bounded set/);
  assert.equal(many.calls.length, 1);
});

test('fails when process liveness succeeds but readiness fails', async () => {
  for (const status of [200, 503]) {
    const api = fakeRelease({ change: path => path === '/api/v1/ready'
      ? new Response(JSON.stringify({ status: 'NOT_READY', service: 'dynamic-ai-world-cup' }),
        { status, headers: { 'content-type': 'application/json' } }) : undefined });
    await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), /not ready|HTTP 503/);
    assert(api.calls.some(call => new URL(call.url).pathname === '/api/v1/health'));
  }
});

test('does not include response stream errors in CLI-safe error text', async () => {
  const api = fakeRelease({ change: () => new Response(new ReadableStream({
    start(controller) { controller.error(new Error('private transport detail')); },
  }), { headers: { 'content-type': 'text/html' } }) });
  await assert.rejects(runReleaseSmoke({ fetchImpl: api.fetchImpl }), error =>
    error.message === 'Cannot finish reading the release response.');
});
