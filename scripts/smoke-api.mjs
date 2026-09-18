import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

// Local dev API only. Creates two generation jobs and one synthetic share; no cleanup of user data.
export async function runApiSmoke({ baseUrl = 'http://127.0.0.1:8080', fetchImpl = fetch,
  pause = ms => new Promise(done => setTimeout(done, ms)), pollAttempts = 90 } = {}) {
  const base = new URL(baseUrl);
  if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname)
      || base.username || base.password || base.search || base.hash || base.pathname !== '/') {
    throw new Error('Use an HTTP loopback origin for the local dev server, e.g. http://127.0.0.1:8080.');
  }
  let cookie;
  async function request(path, { body, key, expected = 200 } = {}) {
    const headers = {};
    if (cookie) headers.Cookie = cookie;
    if (key) headers['Idempotency-Key'] = key;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let response;
    try {
      response = await fetchImpl(`${base.origin}/api/v1${path}`, {
        method: key ? 'POST' : 'GET', headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(10000), redirect: 'error',
      });
    } catch {
      throw new Error('Cannot reach the local API. Start the dev server and check the port.');
    }
    const setCookie = response.headers.get('set-cookie');
    if (setCookie) cookie = setCookie.split(';')[0];
    if (response.status !== expected) {
      if (response.status === 429) throw new Error('Generation rate limit reached. Wait ten minutes before rerunning.');
      throw new Error(`API returned HTTP ${response.status}; expected ${expected}. See docs/API_CONTRACT.md.`);
    }
    return response.json();
  }
  async function ready(job) {
    for (let attempt = 0; attempt < pollAttempts; attempt++) {
      const current = await request(`/generation-jobs/${encodeURIComponent(job.jobId)}`);
      if (current.status === 'READY') return current.draftId;
      if (current.status === 'FAILED') throw new Error('Generation failed. Verify that the server uses the dev profile; live engines are not part of this smoke test.');
      assert(['QUEUED', 'RUNNING'].includes(current.status), 'Unexpected job state');
      await pause(1000);
    }
    throw new Error('Generation polling timed out. Inspect the local worker; no new job was submitted.');
  }

  const health = await request('/health');
  assert.equal(health.service, 'dynamic-ai-world-cup');
  assert.equal(health.status, 'UP');
  const input = { prompt: '개발 연결 확인용 합성 후보', size: 8, locale: 'ko-KR', timezone: 'Asia/Seoul' };
  const creationKey = randomUUID();
  const created = await request('/generation-jobs', { body: input, key: creationKey, expected: 202 });
  assert(cookie, 'Server did not issue an anonymous cookie');
  const repeated = await request('/generation-jobs', { body: input, key: creationKey, expected: 202 });
  assert.deepEqual(repeated, created, 'Creation retry changed the job');
  const draftId = await ready(created);
  const draftPath = `/drafts/${encodeURIComponent(draftId)}`;
  const preview = await request(draftPath);
  assert.equal(preview.candidates.length, 8);
  assert(preview.candidates.every(candidate => candidate.name.startsWith('[개발용]')), 'This smoke test requires the synthetic dev engine');
  assert.equal(preview.regenerationsRemaining, 1);
  const regenerated = await request(`${draftPath}/regenerations`, {
    body: { expectedVersion: preview.version }, key: randomUUID(), expected: 202,
  });
  assert.equal(await ready(regenerated), draftId);
  const replacement = await request(draftPath);
  assert.equal(replacement.version, preview.version + 1);
  assert.equal(replacement.regenerationsRemaining, 0);
  const started = await request(`${draftPath}/start`, {
    body: { expectedVersion: replacement.version }, key: randomUUID(), expected: 201,
  });
  const restarted = await request(`${draftPath}/start`, {
    body: { expectedVersion: replacement.version }, key: randomUUID(), expected: 201,
  });
  assert.deepEqual(restarted, started, 'Start must converge even with a different key');
  assert.deepEqual(started.snapshot.rules, { matchDurationMs: 7000, timeoutMode: 'UNIFORM_RANDOM', undoAllowed: false });
  assert.deepEqual([...started.snapshot.initialOrder].sort(), replacement.candidates.map(candidate => candidate.id).sort());
  const stored = await request(`/snapshots/${encodeURIComponent(started.snapshot.snapshotId)}`);
  assert.deepEqual(stored, started.snapshot);

  // Deterministic telemetry for API validation only, not an implementation/test of browser timer or RNG.
  let round = started.snapshot.initialOrder;
  const events = [];
  while (round.length > 1) {
    const winners = [];
    for (let index = 0; index < round.length; index += 2) {
      const timedOut = events.length % 2 === 1;
      winners.push(round[index]);
      events.push({ eventId: randomUUID(), sequence: events.length, winnerId: round[index],
        reason: timedOut ? 'TIMEOUT_RANDOM' : 'USER_SELECTED', elapsedMs: timedOut ? 7000 : 6999 });
    }
    round = winners;
  }
  const sessionPath = `/sessions/${encodeURIComponent(started.sessionId)}`;
  const selectionKey = randomUUID();
  const ack = await request(`${sessionPath}/selections`, { body: { events }, key: selectionKey });
  assert.equal(ack.status, 'COMPLETED');
  assert.equal(ack.nextSequence, 7);
  assert.equal(ack.championId, round[0]);
  assert.deepEqual(await request(`${sessionPath}/selections`, { body: { events }, key: selectionKey }), ack);
  const share = await request(`${sessionPath}/shares`, { key: randomUUID(), expected: 201 });
  cookie = undefined; // Replay as a new anonymous user.
  const sharePath = `/shares/${encodeURIComponent(share.token)}`;
  const shared = await request(sharePath);
  assert.deepEqual(shared.snapshot, started.snapshot);
  assert.equal(shared.championId, ack.championId);
  const replay = await request(`${sharePath}/sessions`, { key: randomUUID(), expected: 201 });
  assert.deepEqual(replay.snapshot, started.snapshot);
  assert.notEqual(replay.sessionId, started.sessionId);
  return { candidates: 8, regeneration: 1, selections: 7, sameSnapshotReplay: true, syntheticOnly: true };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (process.argv.length > 3) throw new Error('Usage: npm run api:smoke -- [http://127.0.0.1:8080]');
    const result = await runApiSmoke({ baseUrl: process.argv[2] });
    console.log(`Local dev API smoke passed: ${JSON.stringify(result)}`);
    console.log('Two generation jobs and synthetic play/share records were created in your local DB. No browser UI or live AI quality was tested.');
  } catch (error) {
    console.error(`API smoke failed: ${error.message}`);
    process.exitCode = 1;
  }
}
