import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const originError = 'Use an exact HTTPS origin, or an HTTP loopback origin for local checks; omit credentials, paths, queries and fragments.';

function releaseOrigin(value) {
  let base;
  try { base = new URL(value); } catch { throw new Error(originError); }
  if (typeof value !== 'string' || (value !== base.origin && value !== `${base.origin}/`)
      || base.username || base.password || base.search || base.hash || base.pathname !== '/'
      || !(base.protocol === 'https:' || (base.protocol === 'http:'
        && ['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)))) {
    throw new Error(originError);
  }
  return base;
}

function attributes(tag) {
  const result = new Map();
  for (const match of tag.matchAll(/([^\s=<>/]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) {
    const name = match[1].toLowerCase();
    assert(!result.has(name), 'HTML contains duplicate attributes.');
    result.set(name, match[2] ?? match[3] ?? match[4]);
  }
  return result;
}

// This checks the JS/CSS tags emitted by Vite, not arbitrary browser HTML or script execution.
function bundledAssets(html, base) {
  assert(/<!doctype\s+html\s*>/i.test(html) && /<div\b[^>]*\bid=["']root["']/i.test(html),
    'Expected the bundled application HTML.');
  assert(!/<base\b/i.test(html), 'Unexpected HTML base URL.');
  const assets = new Map();
  let moduleFound = false;
  for (const match of html.matchAll(/<(script|link)\b[^>]*>/gi)) {
    const attrs = attributes(match[0]);
    const script = match[1].toLowerCase() === 'script';
    const rel = (attrs.get('rel') ?? '').toLowerCase().split(/\s+/);
    const stylesheet = rel.includes('stylesheet');
    if (!script && !stylesheet && !rel.includes('modulepreload')) continue;
    const reference = attrs.get(script ? 'src' : 'href');
    if (script && !reference) continue;
    assert(reference, 'A bundled asset URL is missing.');
    // A strict public path also excludes entities, encoded traversal and API routes.
    assert(/^\/assets\/[A-Za-z0-9_./-]+$/.test(reference)
      && !reference.split('/').some(part => part === '.' || part === '..'),
    'Expected a same-origin /assets/ URL without query, fragment or traversal.');
    const url = new URL(reference, base);
    assert(url.origin === base.origin, 'Cross-origin assets are not allowed.');
    const kind = stylesheet ? 'css' : 'js';
    assert(url.pathname.endsWith(`.${kind}`), 'Unexpected bundled asset extension.');
    assets.set(url.href, kind);
    if (script && attrs.get('type') === 'module') moduleFound = true;
  }
  assert(moduleFound && assets.size > 0 && assets.size <= 64, 'Expected a bounded set of bundled module assets.');
  return assets;
}

async function readLimited(response, limit) {
  assert(response.body, 'Expected a response body.');
  const reader = response.body.getReader();
  const chunks = [];
  let bytes = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      assert(bytes <= limit, 'Release response exceeded the size limit.');
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  return Buffer.concat(chunks).toString('utf8');
}

// Read-only: no generation, identity, session or share mutation; no cookie jar or credentials.
export async function runReleaseSmoke({ baseUrl = 'http://127.0.0.1:8080', fetchImpl = fetch } = {}) {
  const base = releaseOrigin(baseUrl);
  async function get(pathOrUrl, { status = 200, type, maxBytes = 256 * 1024 } = {}) {
    const url = new URL(pathOrUrl, base);
    assert(url.origin === base.origin, 'Cross-origin requests are not allowed.');
    let response;
    try {
      response = await fetchImpl(url.href, { method: 'GET', credentials: 'omit', redirect: 'error',
        headers: { Accept: type === 'html' ? 'text/html' : type === 'json' ? 'application/json' : '*/*' },
        signal: AbortSignal.timeout(10000) });
    } catch {
      throw new Error('Cannot read the release endpoint; check reachability, TLS and redirects.');
    }
    assert(!response.redirected && (!response.url || response.url === url.href), 'Redirected release responses are not allowed.');
    if (response.status !== status) {
      await response.body?.cancel().catch(() => {});
      throw new Error(`Release endpoint returned HTTP ${response.status}; expected ${status}.`);
    }
    if (status === 404) {
      await response.body?.cancel().catch(() => {});
      return;
    }
    const contentType = (response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
    const expectedTypes = { html: ['text/html'], json: ['application/json'],
      js: ['text/javascript', 'application/javascript'], css: ['text/css'] };
    assert(expectedTypes[type]?.includes(contentType), 'Unexpected release response content type.');
    let body;
    try { body = await readLimited(response, maxBytes); }
    catch (error) {
      if (error instanceof assert.AssertionError) throw error;
      throw new Error('Cannot finish reading the release response.');
    }
    assert(body.trim().length > 0, 'Release response was empty.');
    return body;
  }

  const root = await get('/', { type: 'html' });
  const rootAssets = bundledAssets(root, base);
  // A nonexistent token checks the deep-link document, without requesting share data or creating a session.
  const share = await get(`/shares/release-smoke-${randomUUID()}`, { type: 'html' });
  const shareAssets = bundledAssets(share, base);
  assert(share === root, 'Root and share deep link must serve the same bundled application.');
  const assets = new Map([...rootAssets, ...shareAssets]);
  for (const [url, kind] of assets) await get(url, { type: kind, maxBytes: 4 * 1024 * 1024 });
  for (const [path, expected] of [['/api/v1/health', 'UP'], ['/api/v1/ready', 'READY']]) {
    let health;
    try { health = JSON.parse(await get(path, { type: 'json', maxBytes: 64 * 1024 })); }
    catch (error) {
      if (error instanceof SyntaxError) throw new Error('Health/readiness endpoint did not return valid JSON.');
      throw error;
    }
    assert(health?.service === 'dynamic-ai-world-cup', 'Unexpected health/readiness service.');
    assert(health?.status === expected, 'Health/readiness endpoint is not ready.');
  }
  await get(`/api/v1/release-smoke-missing-${randomUUID()}`, { status: 404 });
  await get(`/assets/release-smoke-missing-${randomUUID()}.js`, { status: 404 });
  return { root: true, shareDeepLink: true, assets: assets.size, health: true, ready: true,
    apiNotFound: true, assetNotFound: true, readOnly: true };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (process.argv.length > 3) throw new Error('Usage: node scripts/smoke-release.mjs [https://your-service.example]');
    console.log(`Read-only release smoke passed: ${JSON.stringify(await runReleaseSmoke({ baseUrl: process.argv[2] }))}`);
    console.log('No generation, session or share was created. This checks delivery/readiness, not live AI quality or billing approval.');
  } catch (error) {
    console.error(`Release smoke failed: ${error.message}`);
    process.exitCode = 1;
  }
}
