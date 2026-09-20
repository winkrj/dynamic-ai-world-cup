import test from 'node:test';
import type { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { setImmediate as settle } from 'node:timers/promises';
import { AppController } from '../src/app/controller.ts';
import { encodeFlow } from '../src/app/checkpoint.ts';
import type { Flow, PlayFlow } from '../src/app/checkpoint.ts';
import { ApiFailure } from '../src/api/client.ts';
import type { ApiClient } from '../src/api/client.ts';
import type { GenerationRequest, Preview, SelectionAck, SelectionEvent, SessionStart, Size } from '../src/api/types.ts';
import { createPlay } from '../src/play/core.ts';
import { clarifiedPrompt, promptLength } from '../src/app/clarification.ts';

const storageKey = 'worldcup:flow:v1:/';
const input: GenerationRequest = { prompt: '합성 테스트 고민', size: 8, locale: 'ko-KR', timezone: 'Asia/Seoul' };

function session(size: Size = 8): SessionStart {
  const candidates = Array.from({ length: size }, (_, i) => ({ id: `c${i}`, name: `후보 ${i + 1}`, tags: ['합성'], imageUrl: null }));
  return { sessionId: `session-${size}`, snapshot: { snapshotId: `snapshot-${size}`, schemaVersion: 1, title: '합성 테스트', size,
    candidates, initialOrder: candidates.map(c => c.id), frozenAt: '2026-09-18T00:00:00Z',
    rules: { matchDurationMs: 7000, timeoutMode: 'UNIFORM_RANDOM', undoAllowed: false } } };
}

function preview(size: Size = 8, version = 1): Preview {
  return { draftId: 'draft', version, status: 'READY', size, candidateUnit: '취미',
    candidates: session(size).snapshot.candidates, regenerationsRemaining: version === 1 ? 1 : 0 };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function memory(flow?: Flow, key = storageKey) {
  const values = new Map<string, string>(flow ? [[key, encodeFlow(flow, 1000)]] : []);
  return { values, getItem: (name: string) => values.get(name) ?? null,
    setItem: (name: string, value: string) => { values.set(name, value); } };
}

function client(overrides: Partial<ApiClient> = {}): ApiClient {
  const unexpected = async (): Promise<never> => { throw Error('Unexpected API call'); };
  return { createGeneration: unexpected, getJob: unexpected, getPreview: unexpected, regenerate: unexpected,
    start: unexpected, selections: unexpected, share: unexpected, getShare: unexpected, replay: unexpected, ...overrides };
}

function harness(t: TestContext, options: Partial<ConstructorParameters<typeof AppController>[0]> = {}, flow?: Flow) {
  let time = 1000;
  let visible = true;
  let keys = 0;
  const storage = options.storage ?? memory(flow);
  const controller = new AppController({ api: client(), storage, now: () => time, visible: () => visible,
    uuid: () => `operation-${++keys}`, pollMs: 10_000, ...options });
  t.after(() => controller.dispose());
  return { controller, storage, setTime: (value: number) => { time = value; },
    setVisible: (value: boolean) => { visible = value; }, time: () => time };
}

function playFlow(controller: AppController): PlayFlow {
  const f = controller.getFlow();
  assert.equal(f.kind, 'play');
  return f as PlayFlow;
}

function nextChoice(h: ReturnType<typeof harness>, timeout = false): void {
  const match = h.controller.getSnapshot().match!;
  assert.ok(match);
  h.controller.ready(match.sequence);
  h.setTime(h.time() + (timeout ? 7000 : 100));
  h.controller.choose(match.left.id);
  h.setTime(h.time() + 300);
  h.controller.tick();
}

test('generation persists operation before POST and retries ambiguous delivery with exact key/body after refresh', async t => {
  const storage = memory();
  const attempts: { input: GenerationRequest; key: string }[] = [];
  const api = client({
    createGeneration: async (body, key) => {
      const durable = JSON.parse(storage.getItem(storageKey)!).flow;
      assert.equal(durable.kind, 'generation');
      assert.equal(durable.key, key);
      assert.deepEqual(durable.input, body);
      attempts.push({ input: structuredClone(body), key });
      if (attempts.length < 3) throw new ApiFailure('NETWORK_ERROR', { retryable: true });
      return { jobId: 'job', status: 'QUEUED', draftId: null, error: null };
    },
    getJob: async () => ({ jobId: 'job', status: 'READY', draftId: 'draft', error: null }),
    getPreview: async () => preview(),
  });
  const first = harness(t, { api, storage }).controller;
  first.editPrompt('  합성 테스트 고민  '); first.next(); first.chooseSize(8);
  await first.generate();
  assert.equal(first.getSnapshot().canRetry, true);
  await first.retry();
  first.dispose();
  const restored = harness(t, { api, storage, uuid: () => 'must-not-replace-operation' }).controller;
  await restored.resume();
  assert.equal(attempts.length, 3);
  assert.deepEqual(attempts, [attempts[0], attempts[0], attempts[0]]);
  assert.equal(attempts[0].input.prompt, '합성 테스트 고민');
  assert.equal(restored.getSnapshot().screen, 'preview');
});

test('queued and running polling only GETs the original job, including repeated resume', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let posts = 0;
  let gets = 0;
  const h = harness(t, { pollMs: 1000, api: client({
    createGeneration: async () => { posts++; return { jobId: 'job', status: 'QUEUED', draftId: null, error: null }; },
    getJob: async id => {
      assert.equal(id, 'job'); gets++;
      return gets < 3 ? { jobId: id, status: gets === 1 ? 'QUEUED' : 'RUNNING', draftId: null, error: null }
        : { jobId: id, status: 'READY', draftId: 'draft', error: null };
    }, getPreview: async () => preview(),
  }) });
  h.controller.editPrompt(input.prompt); h.controller.next(); h.controller.chooseSize(8);
  await Promise.all([h.controller.generate(), h.controller.generate()]);
  assert.equal(h.controller.getSnapshot().generationStatus, 'QUEUED');
  t.mock.timers.tick(1000); await settle();
  assert.equal(h.controller.getSnapshot().generationStatus, 'RUNNING');
  await h.controller.resume();
  t.mock.timers.tick(10_000); await settle();
  assert.equal(posts, 1); assert.equal(gets, 3);
  assert.equal(h.controller.getSnapshot().screen, 'preview');
});

test('terminal generation failure stops polling and retry; editing requires explicit user action', async t => {
  let posts = 0;
  let gets = 0;
  const h = harness(t, { api: client({
    createGeneration: async () => { posts++; return { jobId: 'failed-job', status: 'QUEUED', draftId: null, error: null }; },
    getJob: async () => { gets++; return { jobId: 'failed-job', status: 'FAILED', draftId: null,
      error: { code: 'CLARIFICATION_REQUIRED', message: 'More input needed', requestId: 'clarify', retryable: false } }; },
  }) });
  h.controller.editPrompt(input.prompt); h.controller.next(); await h.controller.generate();
  assert.equal(h.controller.getSnapshot().busy, false);
  assert.equal(h.controller.getSnapshot().canRetry, false);
  assert.equal(h.controller.getSnapshot().canEdit, true);
  await h.controller.resume(); await h.controller.retry();
  assert.equal(posts, 1); assert.equal(gets, 1);
  h.controller.editInput();
  assert.equal(h.controller.getSnapshot().screen, 'input');
  assert.equal(h.controller.getSnapshot().prompt, input.prompt);
});

test('generation error copy does not invent a semantic cause or imply current facts are supported', async t => {
  for (const code of ['QUALITY_GATE_FAILED', 'CLARIFICATION_REQUIRED'] as const) {
    const h = harness(t, { api: client({
      createGeneration: async () => { throw new ApiFailure(code, { retryable: false }); },
    }) });
    h.controller.editPrompt(input.prompt); h.controller.next(); await h.controller.generate();
    const detail = h.controller.getSnapshot().error?.detail ?? '';
    assert.doesNotMatch(detail, /후보가 부족|조건을 지키면서|대상을 정하지 못/);
    if (code === 'CLARIFICATION_REQUIRED') assert.match(detail, /무엇을 비교해서 고르고 싶은지/);
    else assert.match(detail, /이번 요청의 후보를 완성하지 못했어요/);
  }
});

test('failed generation reload exposes safe recovery without networking until explicit edit and new generation', async t => {
  const storage = memory();
  const keys: string[] = [];
  let gets = 0;
  const privateProviderText = 'Synthetic private provider detail must not enter checkpoint or view';
  const api = client({
    createGeneration: async (_body, key) => {
      keys.push(key);
      return { jobId: `job-${keys.length}`, status: 'QUEUED', draftId: null, error: null };
    },
    getJob: async id => {
      gets++;
      return id === 'job-1' ? { jobId: id, status: 'FAILED', draftId: null,
        error: { code: 'QUALITY_GATE_FAILED', message: privateProviderText, requestId: 'failed-request', retryable: false } }
        : { jobId: id, status: 'READY', draftId: 'draft', error: null };
    },
    getPreview: async () => preview(),
  });
  const first = harness(t, { storage, api }).controller;
  first.editPrompt(input.prompt); first.next(); first.chooseSize(8);
  await first.generate();
  assert.equal(first.getSnapshot().canEdit, true);
  first.dispose();
  const restored = harness(t, { storage, api, uuid: () => 'explicit-new-generation' }).controller;
  assert.ok(restored.getSnapshot().error);
  assert.equal(restored.getSnapshot().canEdit, true);
  assert.equal(restored.getSnapshot().canRetry, false);
  assert.equal(restored.getSnapshot().busy, false);
  assert.equal(storage.getItem(storageKey)!.includes(privateProviderText), false);
  assert.equal(JSON.stringify(restored.getSnapshot()).includes(privateProviderText), false);
  await restored.resume(); await restored.retry();
  assert.equal(keys.length, 1); assert.equal(gets, 1);
  restored.editInput();
  assert.equal(restored.getSnapshot().screen, 'input');
  assert.equal(restored.getSnapshot().prompt, input.prompt);
  restored.next(); await restored.generate();
  assert.deepEqual(keys, [keys[0], 'explicit-new-generation']);
  assert.notEqual(keys[0], keys[1]);
  assert.equal(gets, 2);
  assert.equal(restored.getSnapshot().screen, 'preview');
});

test('Retry-After blocks early retry and only explicit retry at deadline repeats original generation key', async t => {
  const keys: string[] = [];
  const h = harness(t, { api: client({
    createGeneration: async (_body, key) => {
      keys.push(key);
      if (keys.length === 1) throw new ApiFailure('RATE_LIMITED', { status: 429, retryable: true, retryAt: 5000 });
      return { jobId: 'job', status: 'QUEUED', draftId: null, error: null };
    }, getJob: async () => ({ jobId: 'job', status: 'READY', draftId: 'draft', error: null }), getPreview: async () => preview(),
  }) });
  h.controller.editPrompt(input.prompt); h.controller.next(); await h.controller.generate();
  assert.equal(h.controller.getSnapshot().error!.retryAt, 5000);
  h.setTime(4999); await h.controller.retry(); assert.equal(keys.length, 1);
  h.setTime(5000); h.controller.tick(); assert.equal(keys.length, 1);
  await h.controller.retry();
  assert.deepEqual(keys, [keys[0], keys[0]]);
  assert.equal(h.controller.getSnapshot().screen, 'preview');
});

test('initial 429 survives reload and expiry without POST, then edit preserves prompt and size without a request', async t => {
  const storage = memory();
  let posts = 0;
  const api = client({ createGeneration: async () => {
    posts++; throw new ApiFailure('RATE_LIMITED', { status: 429, retryAt: 5000 });
  } });
  const first = harness(t, { storage, api }).controller;
  first.editPrompt(input.prompt); first.next(); first.chooseSize(32); await first.generate();
  assert.equal(first.getSnapshot().canEdit, true);
  assert.equal(first.getSnapshot().busy, false);
  first.dispose();
  const restored = harness(t, { storage, api });
  assert.equal(restored.controller.getSnapshot().error?.retryAt, 5000);
  await restored.controller.resume(); await restored.controller.retry();
  restored.setTime(5000); restored.controller.tick(); await restored.controller.resume();
  assert.equal(posts, 1);
  assert.equal(restored.controller.getSnapshot().canEdit, true);
  restored.controller.editInput();
  assert.equal(restored.controller.getSnapshot().screen, 'input');
  assert.equal(restored.controller.getSnapshot().prompt, input.prompt);
  assert.equal(restored.controller.getSnapshot().size, 32);
  assert.equal(posts, 1);
});

test('legacy clarified 429 checkpoint can edit while waiting without losing its appended answer', async t => {
  const body = { ...input, prompt: clarifiedPrompt(input.prompt, '추가 합성 조건'), size: 32 as const };
  const h = harness(t, {}, { kind: 'generation', input: body, key: 'legacy', clarificationUsed: true,
    manualRetry: true, retryAt: 5000 });
  await h.controller.resume();
  assert.equal(h.controller.getSnapshot().canEdit, true);
  h.controller.editInput();
  assert.deepEqual(h.controller.getFlow(), { kind: 'input', prompt: body.prompt, size: 32, clarificationUsed: true });
});

test('429 without Retry-After is manual and editable after reload without inventing a deadline', async t => {
  const storage = memory();
  let posts = 0;
  const api = client({ createGeneration: async () => { posts++; throw new ApiFailure('RATE_LIMITED', { status: 429 }); } });
  const first = harness(t, { storage, api }).controller;
  first.editPrompt(input.prompt); first.next(); await first.generate(); first.dispose();
  const restored = harness(t, { storage, api }).controller;
  await restored.resume();
  assert.equal(posts, 1);
  assert.equal(restored.getSnapshot().error?.retryAt, undefined);
  assert.equal(restored.getSnapshot().canEdit, true);
  assert.equal(restored.getSnapshot().canRetry, true);
  assert.match(restored.getSnapshot().error!.title, /잠시 쉬었다/);
  restored.editInput(); assert.equal(posts, 1);
});

test('retrying a rejected create clears editable rejection before POST and an ambiguous outcome remains locked to the same operation', async t => {
  const storage = memory({ kind: 'generation', input, key: 'original', manualRetry: true, rejection: 'RATE_LIMITED', retryAt: 1000 });
  const attempts: unknown[] = [];
  const pending = deferred<never>();
  const api = client({ createGeneration: async (body, key) => {
    const durable = JSON.parse(storage.getItem(storageKey)!).flow;
    assert.equal(durable.rejection, undefined);
    assert.equal(durable.retryAt, undefined);
    attempts.push([body, key]);
    if (attempts.length === 1) return pending.promise;
    throw new ApiFailure('NETWORK_ERROR');
  } });
  const first = harness(t, { storage, api }).controller;
  const retry = first.retry();
  assert.equal(first.getSnapshot().canEdit, false);
  first.editInput(); assert.equal(first.getFlow().kind, 'generation');
  pending.reject(new ApiFailure('NETWORK_ERROR')); await retry;
  assert.equal(first.getSnapshot().canEdit, false);
  first.dispose();
  const restored = harness(t, { storage, api }).controller;
  await restored.resume(); restored.editInput(); restored.newCup();
  assert.equal(restored.getFlow().kind, 'generation');
  assert.equal(restored.getSnapshot().canEdit, false);
  assert.equal(attempts.length, 1);
  await restored.retry();
  assert.deepEqual(attempts, [[input, 'original'], [input, 'original']]);
});

test('an accepted job returning 429 cannot be edited or resubmitted as a new generation', async t => {
  let gets = 0;
  const h = harness(t, { api: client({ getJob: async () => {
    gets++; throw new ApiFailure('RATE_LIMITED', { status: 429, retryAt: 5000 });
  } }) }, { kind: 'generation', input, key: 'original', jobId: 'accepted' });
  await h.controller.resume();
  assert.equal(h.controller.getSnapshot().canEdit, false);
  h.controller.editInput(); h.controller.newCup(); await h.controller.generate();
  assert.equal(h.controller.getFlow().kind, 'generation');
  assert.equal(gets, 1);
});

test('grounding failure is terminal rather than clarification and retains the support boundary after reload', async t => {
  const storage = memory();
  let posts = 0;
  const api = client({ createGeneration: async () => {
    posts++; return { jobId: 'grounding', status: 'QUEUED', draftId: null, error: null };
  }, getJob: async () => ({ jobId: 'grounding', status: 'FAILED', draftId: null,
    error: { code: 'GROUNDING_REQUIRED', message: 'Private detail must not be shown', requestId: 'grounding', retryable: false } }) });
  const first = harness(t, { storage, api }).controller;
  first.editPrompt(input.prompt); first.next(); await first.generate();
  assert.equal(first.getSnapshot().screen, 'generating');
  assert.equal(first.getSnapshot().canRetry, false);
  assert.equal(first.getSnapshot().canEdit, true);
  assert.match(first.getSnapshot().error!.detail, /최신 순위, 현재 가격·영업·예약·시청 가능 여부는 확인하지 못해요/);
  assert.match(first.getSnapshot().error!.detail, /일반적인 노래·영화·책 추천은 가능해요/);
  first.dispose();
  const restored = harness(t, { storage, api }).controller;
  await restored.resume(); await restored.retry();
  assert.equal(posts, 1);
  assert.match(restored.getSnapshot().error!.title, /실시간 정보는 확인할 수 없어요/);
  assert.doesNotMatch(storage.getItem(storageKey)!, /Private detail/);
  restored.editInput(); assert.equal(restored.getSnapshot().prompt, input.prompt);
});

test('grounding failure during regeneration keeps the complete prior preview and its replacement allowance', async t => {
  const original = preview(16);
  const h = harness(t, { api: client({ regenerate: async () => ({ jobId: 'regen', status: 'QUEUED', draftId: null, error: null }),
    getJob: async () => ({ jobId: 'regen', status: 'FAILED', draftId: null,
      error: { code: 'GROUNDING_REQUIRED', message: 'private', requestId: 'regen', retryable: false } }) }) },
  { kind: 'preview', input: { ...input, size: 16 }, preview: original });
  await h.controller.regenerate();
  assert.equal(h.controller.getSnapshot().screen, 'preview');
  assert.deepEqual(h.controller.getSnapshot().preview, original);
  assert.equal(h.controller.getSnapshot().previewLocked, false);
  assert.equal(h.controller.getSnapshot().preview!.regenerationsRemaining, 1);
  assert.match(h.controller.getSnapshot().error!.title, /실시간 정보/);
});

test('failed regeneration preserves original full preview and remaining successful regeneration', async t => {
  const original = preview();
  const storage = memory({ kind: 'preview', input, preview: original });
  const h = harness(t, { storage, api: client({
    regenerate: async (id, version, key) => {
      assert.equal(id, original.draftId); assert.equal(version, original.version);
      assert.equal(JSON.parse(storage.getItem(storageKey)!).flow.key, key);
      assert.equal(h.controller.getSnapshot().previewLocked, true);
      assert.deepEqual(h.controller.getSnapshot().preview, original);
      return { jobId: 'regen-job', status: 'QUEUED', draftId: null, error: null };
    },
    getJob: async () => ({ jobId: 'regen-job', status: 'FAILED', draftId: null,
      error: { code: 'QUALITY_GATE_FAILED', message: 'Synthetic rejected set', requestId: 'request-test', retryable: false } }),
  }) });
  await h.controller.regenerate();
  assert.deepEqual(h.controller.getSnapshot().preview, original);
  assert.equal(h.controller.getSnapshot().preview!.regenerationsRemaining, 1);
  assert.equal(h.controller.getSnapshot().previewLocked, false);
  assert.equal(h.controller.getSnapshot().error!.requestId, 'request-test');
});

test('start version conflict retrieves newest preview but never starts it without a fresh explicit choice', async t => {
  let starts = 0;
  const keys: string[] = [];
  const storage = memory({ kind: 'preview', input, preview: preview() });
  const h = harness(t, { storage, api: client({
    start: async (_id, version, key) => {
      starts++; keys.push(key);
      assert.equal(JSON.parse(storage.getItem(storageKey)!).flow.startKey, key);
      if (starts === 1) { assert.equal(version, 1); throw new ApiFailure('VERSION_CONFLICT'); }
      assert.equal(version, 2); return session();
    }, getPreview: async () => preview(8, 2),
  }) });
  await h.controller.start();
  assert.equal(starts, 1); assert.equal(h.controller.getSnapshot().preview!.version, 2);
  assert.equal(h.controller.getSnapshot().previewLocked, false);
  await h.controller.resume(); assert.equal(starts, 1);
  await h.controller.start();
  assert.equal(starts, 2); assert.notEqual(keys[0], keys[1]);
  assert.equal(h.controller.getSnapshot().screen, 'play');
});

test('ambiguous start is locked and refresh repeats the same freeze request before exposing play', async t => {
  const storage = memory({ kind: 'preview', input, preview: preview() });
  const attempts: unknown[] = [];
  const api = client({ start: async (id, version, key) => {
    attempts.push([id, version, key]);
    if (attempts.length === 1) throw new ApiFailure('REQUEST_TIMEOUT');
    return session();
  } });
  const first = harness(t, { storage, api }).controller;
  await first.start();
  assert.equal(first.getSnapshot().previewLocked, true);
  await first.regenerate(); first.editInput();
  assert.equal(first.getFlow().kind, 'preview');
  first.dispose();
  const restored = harness(t, { storage, api, uuid: () => 'new-key-prohibited' }).controller;
  await restored.resume();
  assert.deepEqual(attempts[0], attempts[1]);
  assert.equal(restored.getSnapshot().play!.phase, 'preparation');
});

for (const size of [8, 16, 32] as const) {
  test(`${size}: N−1 decisions queue behind immutable partial upload, then sharing waits for persisted champion`, async t => {
    const firstAck = deferred<SelectionAck>();
    const finalAck = deferred<SelectionAck>();
    const batches: { key: string; events: readonly SelectionEvent[] }[] = [];
    let shares = 0;
    const storage = memory({ kind: 'play', play: createPlay(session(size)), acknowledged: 0 });
    const h = harness(t, { storage, api: client({
      selections: async (id, events, key) => {
        assert.equal(id, `session-${size}`);
        const pending = JSON.parse(storage.getItem(storageKey)!).flow.pending;
        assert.equal(pending.key, key); assert.deepEqual(pending.events, events);
        batches.push({ key, events: structuredClone(events) });
        return batches.length === 1 ? firstAck.promise : finalAck.promise;
      },
      share: async (_id, key) => {
        shares++;
        assert.equal(JSON.parse(storage.getItem(storageKey)!).flow.shareKey, key);
        return { token: 'shared', url: 'https://example.test/shares/shared', snapshotId: `snapshot-${size}`,
          championId: h.controller.getSnapshot().champion!.id };
      },
    }) });
    for (let i = 0; i < size - 1; i++) nextChoice(h, i === 0);
    assert.equal(batches.length, 1);
    assert.equal(batches[0].events.length, 1);
    assert.equal(batches[0].events[0].reason, 'TIMEOUT_RANDOM');
    assert.deepEqual(playFlow(h.controller).pending, batches[0]);
    assert.equal(h.controller.getSnapshot().screen, 'champion');
    assert.equal(h.controller.getSnapshot().saved, false);
    await h.controller.share(); assert.equal(shares, 0);
    firstAck.resolve({ nextSequence: 1, status: 'PLAYING', championId: null });
    await settle();
    assert.equal(batches.length, 2);
    assert.notEqual(batches[1].key, batches[0].key);
    assert.deepEqual(batches[1].events.map(event => event.sequence), Array.from({ length: size - 2 }, (_, i) => i + 1));
    assert.equal(playFlow(h.controller).acknowledged, 1);
    await h.controller.share(); assert.equal(shares, 0);
    finalAck.resolve({ nextSequence: size - 1, status: 'COMPLETED', championId: h.controller.getSnapshot().champion!.id });
    await settle();
    assert.equal(h.controller.getSnapshot().saved, true);
    assert.equal(playFlow(h.controller).acknowledged, size - 1);
    await h.controller.share(); await h.controller.share();
    assert.equal(shares, 1);
    assert.equal(h.controller.getSnapshot().shareUrl, 'https://example.test/shares/shared');
  });
}

test('lost partial ACK survives refresh and retries identical batch while later events remain queued', async t => {
  const storage = memory({ kind: 'play', play: createPlay(session()), acknowledged: 0 });
  const attempts: { key: string; events: readonly SelectionEvent[] }[] = [];
  const lost = deferred<SelectionAck>();
  const api = client({ selections: async (_id, events, key) => {
    attempts.push({ key, events: structuredClone(events) });
    if (attempts.length === 1) return lost.promise;
    return { nextSequence: events.at(-1)!.sequence + 1, status: 'PLAYING', championId: null };
  } });
  const h = harness(t, { storage, api });
  nextChoice(h); nextChoice(h);
  lost.reject(new ApiFailure('NETWORK_ERROR')); await settle();
  h.controller.dispose();
  const restored = harness(t, { storage, api, uuid: () => 'post-refresh-key' }).controller;
  await restored.resume();
  assert.equal(attempts.length, 3);
  assert.deepEqual(attempts[0], attempts[1]);
  assert.deepEqual(attempts[2].events.map(event => event.sequence), [1]);
  assert.equal(attempts[2].key, 'post-refresh-key');
  assert.equal(playFlow(restored).acknowledged, 2);
  assert.equal(playFlow(restored).play.events.length, 2);
});

test('failed upload does not auto-retry on later choices and stale ACK cannot erase the pending batch', async t => {
  const batches: { key: string; events: readonly SelectionEvent[] }[] = [];
  const h = harness(t, { api: client({ selections: async (_id, events, key) => {
    batches.push({ key, events: structuredClone(events) });
    if (batches.length === 1) throw new ApiFailure('NETWORK_ERROR');
    if (batches.length === 2) return { nextSequence: 0, status: 'PLAYING', championId: null };
    return { nextSequence: events.at(-1)!.sequence + 1, status: 'PLAYING', championId: null };
  } }) }, { kind: 'play', play: createPlay(session()), acknowledged: 0 });
  nextChoice(h); await settle();
  nextChoice(h); await settle();
  assert.equal(batches.length, 1);
  assert.equal(playFlow(h.controller).play.events.length, 2);
  await h.controller.retry();
  assert.equal(playFlow(h.controller).acknowledged, 0);
  assert.deepEqual(playFlow(h.controller).pending, batches[0]);
  await h.controller.retry();
  assert.deepEqual(batches[0], batches[1]);
  assert.deepEqual(batches[0], batches[2]);
  assert.deepEqual(batches[3].events.map(event => event.sequence), [1]);
  assert.equal(playFlow(h.controller).acknowledged, 2);
});

test('completed checkpoint cannot enable sharing until exact stored final events are reconfirmed', async t => {
  const storage = memory({ kind: 'play', play: createPlay(session()), acknowledged: 0 });
  const api = client({ selections: async (_id, events) => ({ nextSequence: events.at(-1)!.sequence + 1,
    status: events.at(-1)!.sequence === 6 ? 'COMPLETED' : 'PLAYING',
    championId: events.at(-1)!.sequence === 6 ? events.at(-1)!.winnerId : null }) });
  const h = harness(t, { storage, api });
  for (let i = 0; i < 7; i++) { nextChoice(h); await settle(); }
  assert.equal(h.controller.getSnapshot().saved, true);
  const events = structuredClone(playFlow(h.controller).play.events);
  h.controller.dispose();
  const confirm = deferred<SelectionAck>();
  let shares = 0;
  const restored = harness(t, { storage, api: client({
    selections: async (_id, sent, key) => {
      assert.deepEqual(sent, events);
      assert.equal(JSON.parse(storage.getItem(storageKey)!).flow.pending.key, key);
      return confirm.promise;
    }, share: async () => { shares++; throw Error('Not permitted yet'); },
  }) }).controller;
  assert.equal(restored.getSnapshot().saved, false);
  const resuming = restored.resume();
  await restored.share(); assert.equal(shares, 0);
  confirm.resolve({ nextSequence: 7, status: 'COMPLETED', championId: events.at(-1)!.winnerId });
  await resuming;
  assert.equal(restored.getSnapshot().saved, true);
});

test('clipboard failure preserves generated URL and copy retry never creates another share', async t => {
  let posts = 0;
  let copies = 0;
  const h = harness(t, { copy: async () => { copies++; throw Error('Clipboard denied'); }, api: client({
    selections: async (_id, events) => ({ nextSequence: events.at(-1)!.sequence + 1,
      status: events.at(-1)!.sequence === 6 ? 'COMPLETED' : 'PLAYING',
      championId: events.at(-1)!.sequence === 6 ? events.at(-1)!.winnerId : null }),
    share: async () => { posts++; return { token: 'shared', url: 'https://example.test/shares/shared', snapshotId: 'snapshot-8', championId: 'c0' }; },
  }) }, { kind: 'play', play: createPlay(session()), acknowledged: 0 });
  for (let i = 0; i < 7; i++) { nextChoice(h); await settle(); }
  await h.controller.share();
  await h.controller.copyShare(); await h.controller.copyShare();
  assert.equal(posts, 1); assert.equal(copies, 2);
  assert.equal(h.controller.getSnapshot().shareUrl, 'https://example.test/shares/shared');
  assert.match(h.controller.getSnapshot().notice!, /직접 선택/);
});

test('hidden expired match resolves once on return; next match never auto-starts in a hidden tab', async t => {
  const h = harness(t, { api: client({ selections: async (_id, events) => ({ nextSequence: events.at(-1)!.sequence + 1, status: 'PLAYING', championId: null }) }) },
    { kind: 'play', play: createPlay(session()), acknowledged: 0 });
  h.controller.ready(0);
  assert.equal(h.controller.getSnapshot().play!.deadline, 8000);
  h.setVisible(false); h.setTime(100_000); h.controller.tick(); h.controller.choose('c0');
  assert.equal(playFlow(h.controller).play.events.length, 0);
  h.setVisible(true); h.controller.tick(); h.controller.tick();
  assert.equal(playFlow(h.controller).play.events.length, 1);
  assert.equal(playFlow(h.controller).play.events[0].reason, 'TIMEOUT_RANDOM');
  h.setVisible(false); h.setTime(100_500); h.controller.tick(); h.controller.ready(1);
  assert.equal(playFlow(h.controller).play.phase, 'feedback');
  h.setVisible(true); h.controller.tick();
  assert.equal(playFlow(h.controller).play.phase, 'preparation');
  assert.equal(playFlow(h.controller).play.deadline, null);
  h.setTime(200_000); h.controller.tick();
  assert.equal(playFlow(h.controller).play.events.length, 1);
  h.controller.ready(1);
  assert.equal(playFlow(h.controller).play.deadline, 207_000);
  await settle();
});

test('refresh restores active deadline rather than resetting seven seconds', async t => {
  const storage = memory({ kind: 'play', play: createPlay(session()), acknowledged: 0 });
  const first = harness(t, { storage }).controller;
  first.ready(0); first.dispose();
  const restored = harness(t, { storage, now: () => 4000 }).controller;
  await restored.resume();
  assert.equal(playFlow(restored).play.startedAt, 1000);
  assert.equal(playFlow(restored).play.deadline, 8000);
  assert.equal(playFlow(restored).play.events.length, 0);
});

test('storage write failure prevents mutation and every network request', async t => {
  let calls = 0;
  const storage = memory();
  const h = harness(t, { storage, api: client({ createGeneration: async () => { calls++; throw Error('must not call'); } }) });
  h.controller.editPrompt(input.prompt); h.controller.next();
  storage.setItem = () => { throw Error('Quota exceeded'); };
  await h.controller.generate();
  assert.equal(calls, 0);
  assert.equal(h.controller.getSnapshot().storageBlocked, true);
  assert.equal(h.controller.getFlow().kind, 'size');
  assert.equal(h.controller.getSnapshot().canRetry, false);
});

test('another tab changing durable state locks stale controller before choices or POST', async t => {
  const storage = memory();
  let calls = 0;
  const first = harness(t, { storage }).controller;
  const second = harness(t, { storage, api: client({ createGeneration: async () => { calls++; throw Error('must not call'); } }) }).controller;
  first.editPrompt('첫 번째 탭');
  second.editPrompt('충돌하는 두 번째 탭'); second.next(); await second.generate();
  assert.equal(second.getSnapshot().locked, true);
  assert.equal(calls, 0);
  assert.equal(JSON.parse(storage.getItem(storageKey)!).flow.prompt, '첫 번째 탭');
});

test('storage event read failure safely blocks further work instead of throwing', t => {
  const storage = memory();
  const h = harness(t, { storage });
  storage.getItem = () => { throw Error('Storage access revoked'); };
  assert.doesNotThrow(() => h.controller.storageChanged());
  assert.equal(h.controller.getSnapshot().storageBlocked, true);
});

test('corrupted checkpoint shows recovery notice and never silently restarts saved tournament', async t => {
  const storage = memory(); storage.values.set(storageKey, '{broken');
  let calls = 0;
  const h = harness(t, { storage, api: client({ start: async () => { calls++; return session(); } }) });
  await h.controller.resume();
  assert.equal(calls, 0);
  assert.equal(h.controller.getSnapshot().screen, 'input');
  assert.match(h.controller.getSnapshot().notice!, /복원할 수 없어요/);
});

test('shared replay persists operation before POST and preserves immutable snapshot with no generation', async t => {
  const path = '/shares/shared';
  const key = `worldcup:flow:v1:${path}`;
  const storage = memory(undefined, key);
  const source = session();
  const replayed = { ...source, sessionId: 'new-session' };
  const replayKeys: string[] = [];
  const api = client({ getShare: async () => ({ snapshot: source.snapshot, championId: 'c0' }), replay: async (token, operationKey) => {
    assert.equal(token, 'shared');
    assert.equal(JSON.parse(storage.getItem(key)!).flow.replayKey, operationKey);
    replayKeys.push(operationKey);
    if (replayKeys.length === 1) throw new ApiFailure('NETWORK_ERROR');
    return replayed;
  } });
  const first = harness(t, { path, storage, api }).controller;
  await first.resume(); await first.replay(); first.dispose();
  const restored = harness(t, { path, storage, api, uuid: () => 'must-not-replace-replay' }).controller;
  await restored.resume();
  assert.deepEqual(replayKeys, [replayKeys[0], replayKeys[0]]);
  assert.equal(playFlow(restored).play.sessionId, 'new-session');
  assert.deepEqual(playFlow(restored).play.snapshot, source.snapshot);
  assert.equal(playFlow(restored).play.events.length, 0);
});

test('returning from shared bracket navigates home and preserves existing root tournament checkpoint', async t => {
  const storage = memory({ kind: 'play', play: createPlay(session()), acknowledged: 0 });
  const root = harness(t, { storage }).controller;
  root.ready(0);
  const existingRoot = storage.getItem(storageKey);
  root.dispose();
  const paths: string[] = [];
  const shared = harness(t, { path: '/shares/shared', storage, navigate: path => paths.push(path),
    api: client({ getShare: async () => ({ snapshot: session().snapshot, championId: 'c2' }) }) }).controller;
  await shared.resume();
  assert.equal(shared.getSnapshot().screen, 'share');
  shared.actions.newCup();
  assert.deepEqual(paths, ['/']);
  assert.equal(storage.getItem(storageKey), existingRoot);
  assert.equal(shared.getFlow().kind, 'share');
  shared.dispose();
  const reopened = harness(t, { storage, now: () => 4000 }).controller;
  await reopened.resume();
  assert.equal(reopened.getSnapshot().screen, 'play');
  assert.equal(playFlow(reopened).play.sessionId, 'session-8');
  assert.equal(playFlow(reopened).play.phase, 'active');
  assert.equal(playFlow(reopened).play.deadline, 8000);
  assert.equal(playFlow(reopened).play.events.length, 0);
});

test('clarification preserves original conditions, size and answer across reload; only explicit answer sends a new key', async t => {
  const original = '혼자 할 거야. 운동은 싫고 월 예산은 10만 원이야.\n집에서 30분만 쓸 수 있어.';
  const answer = '새로 시작할 취미를 고르고 싶어요.';
  const posts: { input: GenerationRequest; key: string }[] = [];
  let gets = 0;
  const storage = memory();
  const api = client({ createGeneration: async (body, key) => {
    assert.deepEqual(JSON.parse(storage.getItem(storageKey)!).flow.input, body);
    assert.equal(JSON.parse(storage.getItem(storageKey)!).flow.key, key);
    posts.push({ input: structuredClone(body), key });
    return { jobId: `job-${posts.length}`, status: 'QUEUED', draftId: null, error: null };
  }, getJob: async id => {
    gets++;
    return id === 'job-1' ? { jobId: id, status: 'FAILED', draftId: null,
      error: { code: 'CLARIFICATION_REQUIRED', message: 'private model detail', requestId: 'clarification', retryable: false } }
      : { jobId: id, status: 'READY', draftId: 'draft', error: null };
  }, getPreview: async () => preview(32) });
  const first = harness(t, { storage, api }).controller;
  first.editPrompt(original); first.next(); first.chooseSize(32); await first.generate();
  assert.equal(first.getSnapshot().screen, 'clarification');
  assert.equal(first.getSnapshot().prompt, original);
  assert.equal(first.getSnapshot().size, 32);
  first.editClarification(answer);
  await first.resume(); await first.retry();
  assert.equal(posts.length, 1);
  assert.equal(gets, 1);
  first.dispose();
  const restored = harness(t, { storage, api, uuid: () => 'explicit-answer-key' }).controller;
  await restored.resume();
  assert.equal(restored.getSnapshot().screen, 'clarification');
  assert.equal(restored.getSnapshot().clarificationAnswer, answer);
  assert.equal(restored.getSnapshot().prompt, original);
  assert.equal(posts.length, 1);
  assert(!storage.getItem(storageKey)!.includes('private model detail'));
  await Promise.all([restored.submitClarification(), restored.submitClarification()]);
  assert.equal(posts.length, 2);
  assert.notEqual(posts[0].key, posts[1].key);
  assert.deepEqual(posts[1], { input: { ...posts[0].input, prompt: clarifiedPrompt(original, answer) }, key: 'explicit-answer-key' });
  assert.equal(restored.getSnapshot().screen, 'preview');
  assert.equal(restored.getSnapshot().preview!.size, 32);
});

test('clarification validates the full 500 Unicode-codepoint request without truncating original or answer', async t => {
  const original = '😀'.repeat(480);
  const exactAnswer = '😀'.repeat(500 - promptLength(clarifiedPrompt(original, '')));
  const posted: GenerationRequest[] = [];
  const h = harness(t, { api: client({ createGeneration: async body => {
    posted.push(body); return { jobId: 'job', status: 'QUEUED', draftId: null, error: null };
  }, getJob: async () => ({ jobId: 'job', status: 'READY', draftId: 'draft', error: null }), getPreview: async () => preview() }) },
  { kind: 'clarification', input: { ...input, prompt: original }, answer: '' });
  await h.controller.submitClarification();
  assert.equal(posted.length, 0);
  h.controller.editClarification(`${exactAnswer}😀`);
  await h.controller.submitClarification();
  assert.equal(posted.length, 0);
  assert.equal(h.controller.getSnapshot().clarificationAnswer, `${exactAnswer}😀`);
  assert.equal(h.controller.getSnapshot().prompt, original);
  assert.match(h.controller.getSnapshot().error!.detail, /1자 줄이거나/);
  h.controller.editClarification(exactAnswer);
  await h.controller.submitClarification();
  assert.equal(posted.length, 1);
  assert.equal(promptLength(posted[0].prompt), 500);
  assert.equal(posted[0].prompt, clarifiedPrompt(original, exactAnswer));
});

test('original prompt editing retains over-limit text, counts Unicode points and prevents premature generation', async t => {
  const h = harness(t);
  const tooLong = '😀'.repeat(501);
  h.controller.editPrompt(tooLong); h.controller.next(); await h.controller.generate();
  assert.equal(h.controller.getSnapshot().screen, 'input');
  assert.equal(h.controller.getSnapshot().prompt, tooLong);
  assert.match(h.controller.getSnapshot().error!.detail, /500자/);
  h.controller.editPrompt('😀'.repeat(500)); h.controller.next();
  assert.equal(h.controller.getSnapshot().screen, 'size');
});

test('a repeated clarification failure and subsequent edit never ask another question in the same creation flow', async t => {
  let posts = 0;
  const storage = memory({ kind: 'clarification', input, answer: '취미를 고를 거야' });
  const api = client({ createGeneration: async () => {
    posts++; return { jobId: `job-${posts}`, status: 'QUEUED', draftId: null, error: null };
  }, getJob: async id => ({ jobId: id, status: 'FAILED', draftId: null,
    error: { code: 'CLARIFICATION_REQUIRED', message: 'private repeated question', requestId: 'again', retryable: false } }) });
  const first = harness(t, { storage, api }).controller;
  await first.submitClarification();
  assert.equal(first.getSnapshot().screen, 'generating');
  assert.equal(first.getSnapshot().canEdit, true);
  assert.equal(first.getSnapshot().canRetry, false);
  first.dispose();
  const restored = harness(t, { storage, api, uuid: () => 'edited-key' }).controller;
  await restored.resume();
  assert.equal(posts, 1);
  restored.editInput(); restored.next(); await restored.generate();
  assert.equal(posts, 2);
  assert.equal(restored.getSnapshot().screen, 'generating');
  assert.equal(restored.getSnapshot().canEdit, true);
  restored.newCup();
  assert.deepEqual(restored.getFlow(), { kind: 'input', prompt: '', size: 16 });
});

test('editing the original instead of answering consumes the one clarification opportunity without losing conditions', async t => {
  const h = harness(t, {}, { kind: 'clarification', input: { ...input, size: 32 }, answer: '작성 중인 답변' });
  h.controller.editInput();
  assert.deepEqual(h.controller.getFlow(), { kind: 'input', prompt: input.prompt, size: 32, clarificationUsed: true });
  h.controller.next(); h.controller.back();
  assert.equal(h.controller.getFlow().kind, 'input');
  assert.equal((h.controller.getFlow() as { clarificationUsed?: true }).clarificationUsed, true);
});

test('clarification answer 429 preserves retry deadline and exact new request across reload without automatic POST', async t => {
  const posts: { input: GenerationRequest; key: string }[] = [];
  const storage = memory({ kind: 'clarification', input, answer: '취미를 고를 거야' });
  const api = client({ createGeneration: async (body, key) => {
    posts.push({ input: structuredClone(body), key });
    if (posts.length === 1) throw new ApiFailure('RATE_LIMITED', { status: 429, retryAt: 5000, retryable: true });
    return { jobId: 'answer-job', status: 'QUEUED', draftId: null, error: null };
  }, getJob: async () => ({ jobId: 'answer-job', status: 'READY', draftId: 'draft', error: null }), getPreview: async () => preview() });
  const first = harness(t, { storage, api }).controller;
  await first.submitClarification(); first.dispose();
  const h = harness(t, { storage, api, uuid: () => 'must-not-change-answer-key' });
  assert.equal(h.controller.getSnapshot().error!.retryAt, 5000);
  assert.equal(h.controller.getSnapshot().busy, false);
  await h.controller.resume(); await h.controller.retry();
  h.setTime(5000); h.controller.tick(); await h.controller.resume();
  assert.equal(posts.length, 1);
  await h.controller.retry();
  assert.equal(posts.length, 2);
  assert.deepEqual(posts[1], posts[0]);
  assert.equal(h.controller.getSnapshot().screen, 'preview');
});

test('ambiguous answer delivery remains manual after reload and retry keeps the answered body/key', async t => {
  const storage = memory({ kind: 'clarification', input, answer: '취미를 고를 거야' });
  const posts: { input: GenerationRequest; key: string }[] = [];
  const api = client({ createGeneration: async (body, key) => {
    posts.push({ input: structuredClone(body), key }); throw new ApiFailure('NETWORK_ERROR');
  } });
  const first = harness(t, { storage, api }).controller;
  await first.submitClarification(); first.dispose();
  const restored = harness(t, { storage, api }).controller;
  await restored.resume();
  assert.equal(posts.length, 1);
  assert.equal(restored.getSnapshot().canRetry, true);
  await restored.retry();
  assert.deepEqual(posts, [posts[0], posts[0]]);
});

test('CLARIFICATION_REQUIRED during regeneration preserves the old preview and never opens a question', async t => {
  const original = preview(32);
  const h = harness(t, { api: client({ regenerate: async () => ({ jobId: 'regen', status: 'QUEUED', draftId: null, error: null }),
    getJob: async () => ({ jobId: 'regen', status: 'FAILED', draftId: null,
      error: { code: 'CLARIFICATION_REQUIRED', message: 'private', requestId: 'regen', retryable: false } }) }) },
  { kind: 'preview', input: { ...input, size: 32 }, preview: original });
  await h.controller.regenerate();
  assert.equal(h.controller.getSnapshot().screen, 'preview');
  assert.deepEqual(h.controller.getSnapshot().preview, original);
  assert.equal(h.controller.getSnapshot().previewLocked, false);
});
