import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runApiSmoke } from './smoke-api.mjs';

const fixture = JSON.parse(await readFile(new URL('../contracts/fixtures/snapshot-8.json', import.meta.url), 'utf8'));
function fakeApi({ pending = false, failure = false, changedReplay = false } = {}) {
  const calls = [];
  const snapshot = { ...structuredClone(fixture), snapshotId: 'snapshot' };
  snapshot.candidates.forEach((candidate, index) => candidate.name = `[개발용] 후보 ${index + 1}`);
  let version = 1;
  let creationKey;
  let selectionKey;
  const reply = (value, status = 200, cookie) => new Response(JSON.stringify(value), {
    status, headers: { 'content-type': 'application/json', ...(cookie ? { 'set-cookie': cookie } : {}) },
  });
  async function fetchImpl(url, options) {
    const path = new URL(url).pathname.replace('/api/v1', '');
    calls.push({ path, ...options });
    assert.equal(options.redirect, 'error');
    if (path === '/health') return reply({ status: 'UP', service: 'dynamic-ai-world-cup' });
    if (path === '/generation-jobs') {
      if (creationKey) { assert.equal(options.headers['Idempotency-Key'], creationKey); assert.equal(options.headers.Cookie, 'worldcup_actor=owner'); }
      creationKey = options.headers['Idempotency-Key'];
      return reply({ jobId: 'job', status: 'QUEUED', draftId: null, error: null }, 202, 'worldcup_actor=owner; HttpOnly');
    }
    if (path.startsWith('/generation-jobs/')) return reply({ jobId: 'job', status: failure ? 'FAILED' : pending ? 'RUNNING' : 'READY', draftId: pending ? null : 'draft', error: null });
    if (path === '/drafts/draft') return reply({ draftId: 'draft', version, status: 'READY', size: 8,
      candidates: snapshot.candidates, regenerationsRemaining: 2 - version });
    if (path === '/drafts/draft/regenerations') {
      assert.deepEqual(JSON.parse(options.body), { expectedVersion: 1 }); version = 2;
      return reply({ jobId: 'regen', status: 'QUEUED', draftId: null, error: null }, 202);
    }
    if (path === '/drafts/draft/start') {
      assert.deepEqual(JSON.parse(options.body), { expectedVersion: 2 });
      return reply({ sessionId: 'session', snapshot }, 201);
    }
    if (path === '/snapshots/snapshot') return reply(snapshot);
    if (path === '/sessions/session/selections') {
      const { events } = JSON.parse(options.body);
      assert.equal(events.length, 7);
      assert.deepEqual(events.map(event => event.sequence), [0, 1, 2, 3, 4, 5, 6]);
      assert.equal(events[0].elapsedMs, 6999); assert.equal(events[1].elapsedMs, 7000);
      if (selectionKey) assert.equal(options.headers['Idempotency-Key'], selectionKey);
      selectionKey = options.headers['Idempotency-Key'];
      return reply({ nextSequence: 7, status: 'COMPLETED', championId: events.at(-1).winnerId });
    }
    if (path === '/sessions/session/shares') {
      assert.equal(options.body, undefined);
      return reply({ token: 'token', snapshotId: 'snapshot', championId: snapshot.initialOrder[0], url: 'http://127.0.0.1:5173/shares/token' }, 201);
    }
    if (path === '/shares/token') {
      assert.equal(options.headers.Cookie, undefined);
      return reply({ snapshot, championId: snapshot.initialOrder[0] });
    }
    if (path === '/shares/token/sessions') {
      assert.equal(options.headers.Cookie, undefined); assert.equal(options.body, undefined);
      return reply({ sessionId: 'replay', snapshot: changedReplay ? { ...snapshot, snapshotId: 'wrong' } : snapshot }, 201, 'worldcup_actor=replayer; HttpOnly');
    }
    throw new Error(`Unexpected test request: ${path}`);
  }
  return { fetchImpl, calls };
}

test('runs the documented flow with cookie retention, stable retry keys and bodyless share/replay', async () => {
  const api = fakeApi();
  assert.deepEqual(await runApiSmoke({ fetchImpl: api.fetchImpl }), {
    candidates: 8, regeneration: 1, selections: 7, sameSnapshotReplay: true, syntheticOnly: true,
  });
  assert(api.calls.every(call => call.signal instanceof AbortSignal));
});
test('refuses remote origins, credentials and API paths before any network request', async () => {
  for (const baseUrl of ['https://example.com', 'http://example.com', 'http://user:pass@localhost:8080', 'http://localhost:8080/api/v1', 'http://localhost:8080?remote=true']) {
    await assert.rejects(runApiSmoke({ baseUrl, fetchImpl: () => assert.fail('Network must not be used') }), /loopback origin/);
  }
});
test('polling is bounded and never resubmits generation', async () => {
  const api = fakeApi({ pending: true }); let pauses = 0;
  await assert.rejects(runApiSmoke({ fetchImpl: api.fetchImpl, pollAttempts: 3, pause: async () => { pauses++; } }), /polling timed out/);
  assert.equal(pauses, 3);
  assert.equal(api.calls.filter(call => call.path === '/generation-jobs').length, 2); // Same idempotent request only.
});
test('terminal job failure stops before any draft/start/share operation', async () => {
  const api = fakeApi({ failure: true });
  await assert.rejects(runApiSmoke({ fetchImpl: api.fetchImpl }), /Generation failed/);
  assert(!api.calls.some(call => call.path.startsWith('/drafts/')));
});
test('a changed replay snapshot fails the smoke result', async () => {
  const api = fakeApi({ changedReplay: true });
  await assert.rejects(runApiSmoke({ fetchImpl: api.fetchImpl }), assert.AssertionError);
});
test('rate limit and unreachable API return safe actionable errors without dumping responses', async () => {
  await assert.rejects(runApiSmoke({ fetchImpl: async () => new Response('private response', { status: 429 }) }), /no reset time was supplied/);
  await assert.rejects(runApiSmoke({ fetchImpl: async () => new Response('private response', { status: 429, headers: { 'Retry-After': '86400' } }) }), /Retry after 86400 seconds/);
  await assert.rejects(runApiSmoke({ fetchImpl: async () => { throw new Error('sensitive detail'); } }), /Start the dev server/);
});
