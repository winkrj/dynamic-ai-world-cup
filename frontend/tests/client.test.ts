import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ApiFailure, createApiClient, errorFromJob } from '../src/api/client.ts';
import type { ApiError, GenerationRequest, SelectionEvent, Snapshot } from '../src/api/types.ts';

const snapshot: Snapshot = JSON.parse(readFileSync(new URL('../../contracts/fixtures/snapshot-8.json', import.meta.url), 'utf8'));
const preview = JSON.parse(readFileSync(new URL('../../contracts/fixtures/preview-8.json', import.meta.url), 'utf8'));
const input: GenerationRequest = { prompt: '합성 테스트', size: 8, locale: 'ko-KR', timezone: 'Asia/Seoul' };
const event: SelectionEvent = { eventId: 'event', sequence: 0, winnerId: snapshot.initialOrder[0], reason: 'USER_SELECTED', elapsedMs: 1000 };
const job = { jobId: 'job', status: 'QUEUED', draftId: null, error: null };
function json(value: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

test('typed methods use exact paths/payloads/cookie/idempotency; sharing POSTs have no body', async () => {
  const calls: { path: string; options: RequestInit }[] = [];
  const responses = [job, job, preview, job, { sessionId: 'session', snapshot }, { nextSequence: 1, status: 'PLAYING', championId: null },
    { token: 'token', url: 'https://example.test/shares/token', snapshotId: snapshot.snapshotId, championId: snapshot.initialOrder[0] },
    { snapshot, championId: snapshot.initialOrder[0] }, { sessionId: 'replay', snapshot }];
  const client = createApiClient((async (path, options) => { calls.push({ path: String(path), options: options! }); return json(responses.shift()); }) as typeof fetch);
  await client.createGeneration(input, 'generate-key');
  await client.getJob('job/id');
  await client.getPreview('draft');
  await client.regenerate('draft', 1, 'regen-key');
  await client.start('draft', 2, 'start-key');
  await client.selections('session', [event], 'selection-key');
  await client.share('session', 'share-key');
  await client.getShare('token');
  await client.replay('token', 'replay-key');
  assert.deepEqual(calls.map(call => call.path), ['/api/v1/generation-jobs', '/api/v1/generation-jobs/job%2Fid', '/api/v1/drafts/draft',
    '/api/v1/drafts/draft/regenerations', '/api/v1/drafts/draft/start', '/api/v1/sessions/session/selections',
    '/api/v1/sessions/session/shares', '/api/v1/shares/token', '/api/v1/shares/token/sessions']);
  assert.deepEqual(JSON.parse(calls[0].options.body as string), input);
  assert.deepEqual(JSON.parse(calls[3].options.body as string), { expectedVersion: 1 });
  assert.deepEqual(JSON.parse(calls[4].options.body as string), { expectedVersion: 2 });
  assert.deepEqual(JSON.parse(calls[5].options.body as string), { events: [event] });
  for (const [index, call] of calls.entries()) {
    const headers = call.options.headers as Record<string, string>;
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.cache, 'no-store');
    assert.equal(call.options.redirect, 'error');
    if ([1, 2, 7].includes(index)) assert.equal(headers['Idempotency-Key'], undefined);
    else assert.ok(headers['Idempotency-Key'].endsWith('-key'));
    if ([1, 2, 6, 7, 8].includes(index)) {
      assert.equal(call.options.body, undefined);
      assert.equal(headers['Content-Type'], undefined);
    }
  }
});

test('structured errors expose only safe messages with status, request ID and Retry-After', async () => {
  const secret = 'provider raw token do not display';
  const error = { code: 'RATE_LIMITED', message: secret, retryable: true, requestId: 'request-1' };
  let count = 0;
  const client = createApiClient((async () => { count++; return json(error, 429, { 'Retry-After': '600' }); }) as typeof fetch, { now: () => 1000 });
  await assert.rejects(client.createGeneration(input, 'stable-key'), failure => {
    assert.ok(failure instanceof ApiFailure);
    assert.equal(failure.code, 'RATE_LIMITED');
    assert.equal(failure.status, 429);
    assert.equal(failure.retryAt, 601_000);
    assert.equal(failure.requestId, 'request-1');
    assert.equal(failure.retryable, true);
    assert.ok(!failure.message.includes(secret));
    return true;
  });
  assert.equal(count, 1, 'No implicit POST retries');
});

test('explicit retry retains the same operation key/body', async () => {
  const attempts: RequestInit[] = [];
  const client = createApiClient((async (_path, options) => {
    attempts.push(options!);
    if (attempts.length === 1) throw new TypeError('offline private detail');
    return json(job);
  }) as typeof fetch);
  await assert.rejects(client.createGeneration(input, 'stable'), { code: 'NETWORK_ERROR', retryable: true });
  await client.createGeneration(input, 'stable');
  assert.equal(attempts.length, 2);
  assert.deepEqual(attempts[0].headers, attempts[1].headers);
  assert.equal(attempts[0].body, attempts[1].body);
});

test('bodyless/unstructured server failure is safe; HTTP-date Retry-After uses receipt time', async () => {
  const client = createApiClient((async () => new Response('<html>private upstream detail</html>', {
    status: 503, headers: { 'Retry-After': 'Fri, 18 Sep 2026 01:00:00 GMT', 'X-Request-Id': 'header-id' },
  })) as typeof fetch, { now: () => Date.parse('2026-09-18T00:00:00Z') });
  await assert.rejects(client.getJob('job'), failure => {
    assert.ok(failure instanceof ApiFailure);
    assert.equal(failure.code, 'INTERNAL_ERROR');
    assert.equal(failure.retryAt, Date.parse('2026-09-18T01:00:00Z'));
    assert.equal(failure.requestId, 'header-id');
    assert.ok(!failure.message.includes('private'));
    return true;
  });
});

test('network timeout and caller abort are distinct; pre-abort makes no request', async () => {
  let count = 0;
  const waiting = (async (_path, options) => {
    count++;
    return await new Promise<Response>((_resolve, reject) => options!.signal!.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true }));
  }) as typeof fetch;
  const timeoutClient = createApiClient(waiting, { timeoutMs: 10 });
  await assert.rejects(timeoutClient.getJob('job'), { code: 'REQUEST_TIMEOUT', retryable: true });
  const controller = new AbortController();
  const aborted = createApiClient(waiting).getJob('job', controller.signal);
  controller.abort();
  await assert.rejects(aborted, { code: 'ABORTED', retryable: false });
  await assert.rejects(createApiClient(waiting).getJob('job', controller.signal), { code: 'ABORTED' });
  assert.equal(count, 2);
});

test('malformed successful responses cannot reach preview/play/share', async () => {
  const cases = [
    { value: { ...preview, size: 32 }, run: (client: ReturnType<typeof createApiClient>) => client.getPreview('draft') },
    { value: { sessionId: 'session', snapshot: { ...snapshot, initialOrder: Array(8).fill('x') } }, run: (client: ReturnType<typeof createApiClient>) => client.start('draft', 1, 'key') },
    { value: { snapshot, championId: 'not-candidate' }, run: (client: ReturnType<typeof createApiClient>) => client.getShare('token') },
    { value: { ...job, status: 'READY', draftId: null }, run: (client: ReturnType<typeof createApiClient>) => client.getJob('job') },
    { value: { nextSequence: 99, status: 'COMPLETED', championId: 'x' }, run: (client: ReturnType<typeof createApiClient>) => client.selections('session', [event], 'key') },
    { value: { token: 'x', url: 'javascript:alert(1)', snapshotId: 's', championId: 'c' }, run: (client: ReturnType<typeof createApiClient>) => client.share('session', 'key') },
  ];
  for (const entry of cases) {
    const client = createApiClient((async () => json(entry.value)) as typeof fetch);
    await assert.rejects(entry.run(client), { code: 'INVALID_RESPONSE' });
  }
  await assert.rejects(createApiClient((async () => new Response('')) as typeof fetch).getJob('job'), { code: 'INVALID_RESPONSE' });
});

test('job failure uses safe local wording and invalid keys do not send requests', async () => {
  const failure = errorFromJob({ code: 'QUALITY_GATE_FAILED', message: 'raw internal', requestId: 'id', retryable: false } as ApiError);
  assert.equal(failure.code, 'QUALITY_GATE_FAILED');
  assert.ok(!failure.message.includes('raw internal'));
  let count = 0;
  const client = createApiClient((async () => { count++; return json(job); }) as typeof fetch);
  await assert.rejects(client.createGeneration(input, ''), { code: 'INVALID_INPUT' });
  await assert.rejects(client.createGeneration(input, 'bad\r\nheader'), { code: 'INVALID_INPUT' });
  assert.equal(count, 0);
});
