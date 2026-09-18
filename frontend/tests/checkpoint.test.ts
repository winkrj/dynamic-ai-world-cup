import test from 'node:test';
import assert from 'node:assert/strict';
import { encodeFlow, restoreFlow } from '../src/app/checkpoint.ts';
import type { Flow, PlayFlow } from '../src/app/checkpoint.ts';
import { advance, createPlay, currentMatch, decide, ready } from '../src/play/core.ts';
import type { GenerationRequest, SessionStart } from '../src/api/types.ts';

const input: GenerationRequest = { prompt: '합성 테스트', size: 8, locale: 'ko-KR', timezone: 'Asia/Seoul' };
function session(): SessionStart {
  const candidates = Array.from({ length: 8 }, (_, i) => ({ id: `c${i}`, name: `후보 ${i}`, tags: ['합성'], imageUrl: null }));
  return { sessionId: 'session', snapshot: { snapshotId: 'snapshot', schemaVersion: 1, title: '테스트', size: 8,
    candidates, initialOrder: candidates.map(c => c.id), frozenAt: '2026-09-18T00:00:00Z',
    rules: { matchDurationMs: 7000, timeoutMode: 'UNIFORM_RANDOM', undoAllowed: false } } };
}
function progressed(count: number): PlayFlow {
  let play = createPlay(session());
  for (let i = 0; i < count; i++) {
    play = ready(play, 1000 + i * 1000);
    play = advance(decide(play, { now: 1100 + i * 1000, winnerId: currentMatch(play)!.left.id, eventId: `event-${i}` }));
  }
  return { kind: 'play', play, acknowledged: 0 };
}

test('generation and preview operation keys and exact bodies survive checkpoint restoration', () => {
  const preview = { draftId: 'draft', version: 1, status: 'READY' as const, size: 8 as const, candidateUnit: '취미',
    candidates: session().snapshot.candidates, regenerationsRemaining: 1 as const };
  const values: Flow[] = [
    { kind: 'generation', input, key: 'create-key' },
    { kind: 'generation', input, key: 'regen-key', jobId: 'job', previous: preview, status: 'RUNNING' },
    { kind: 'preview', input, preview, startKey: 'freeze-key' },
  ];
  for (const flow of values) assert.deepEqual(restoreFlow(encodeFlow(flow, 1000), 1500), { flow });
});

test('active play checkpoint retains exact deadline, snapshot order and unsent events', () => {
  const flow = progressed(2);
  flow.play = ready(flow.play, 3000);
  flow.acknowledged = 1;
  flow.pending = { key: 'batch-key', events: [flow.play.events[1]] };
  const result = restoreFlow(encodeFlow(flow, 3500), 7000);
  assert.equal(result.warning, undefined);
  assert.deepEqual(result.flow, { ...flow, shareKey: undefined, feedbackUntil: undefined });
  const restored = result.flow as PlayFlow;
  assert.equal(restored.play.deadline, 10_000);
  assert.equal(restored.play.startedAt, 3000);
  assert.deepEqual(restored.pending, flow.pending);
});

test('server ACK/share claims are discarded on reload until final upload is reconfirmed', () => {
  const flow = progressed(7);
  flow.acknowledged = 7;
  flow.ack = { status: 'COMPLETED', nextSequence: 7, championId: 'c0' };
  flow.shareKey = 'persisted-share-key';
  flow.share = { token: 'token', url: 'https://example.test/shares/token', snapshotId: 'snapshot', championId: 'c0' };
  const restored = restoreFlow(encodeFlow(flow, 9000), 10_000).flow as PlayFlow;
  assert.equal(restored.kind, 'play');
  assert.equal(restored.play.phase, 'completed');
  assert.equal(restored.acknowledged, 7);
  assert.equal(restored.ack, undefined);
  assert.equal(restored.share, undefined);
  assert.equal(restored.shareKey, 'persisted-share-key');
});

test('shared cached champion is refetched while replay operation key is retained', () => {
  const result = restoreFlow(encodeFlow({ kind: 'share', token: 'token', replayKey: 'replay-key',
    shared: { snapshot: session().snapshot, championId: 'c0' } }, 1000), 2000);
  assert.deepEqual(result, { flow: { kind: 'share', token: 'token', replayKey: 'replay-key' } });
});

test('pending event mismatch, holes, impossible acknowledgement and malformed gameplay are rejected', () => {
  const original = progressed(3);
  original.pending = { key: 'batch', events: [...original.play.events] };
  const mutations: ((flow: any) => void)[] = [
    flow => { flow.pending.key = ''; },
    flow => { flow.pending.events = []; },
    flow => { flow.pending.events[0].winnerId = 'c7'; },
    flow => { flow.pending.events = [flow.pending.events[0], flow.pending.events[2]]; },
    flow => { flow.pending.events[0].elapsedMs = 7000; },
    flow => { flow.acknowledged = -1; },
    flow => { flow.acknowledged = 4; },
    flow => { flow.acknowledged = 0.5; },
    flow => { flow.play.snapshot.initialOrder[1] = flow.play.snapshot.initialOrder[0]; },
    flow => { flow.play.phase = 'completed'; },
    flow => { flow.feedbackUntil = 'tomorrow'; },
  ];
  for (const mutate of mutations) {
    const envelope = JSON.parse(encodeFlow(original, 5000));
    mutate(envelope.flow);
    const restored = restoreFlow(JSON.stringify(envelope), 6000);
    assert.equal(restored.flow, undefined);
    assert.match(restored.warning!, /복원할 수 없어요/);
  }
});

test('corrupted, unsupported, expired and future checkpoints produce warning without fabricated flow', () => {
  const valid = encodeFlow({ kind: 'input', prompt: '', size: 16 }, 1000);
  for (const raw of ['{', 'null', '[]', JSON.stringify({ version: 2, savedAt: 1000, flow: { kind: 'input', prompt: '', size: 16 } }),
    valid.replace('"size":16', '"size":4')]) {
    assert.equal(restoreFlow(raw, 2000).flow, undefined);
    assert.ok(restoreFlow(raw, 2000).warning);
  }
  assert.deepEqual(restoreFlow(null, 2000), {});
  assert.ok(restoreFlow(valid, 1000 + 86400000 + 1).warning);
  assert.ok(restoreFlow(encodeFlow({ kind: 'input', prompt: '', size: 16 }, 70_000), 1000).warning);
  const play = encodeFlow(progressed(1), 2000);
  assert.ok(restoreFlow(play, 2000 + 29 * 86400000).flow);
  assert.ok(restoreFlow(play, 2000 + 30 * 86400000 + 1).warning);
});

test('clarification and draft answers including over-limit edits survive reload without truncation', () => {
  const values: Flow[] = [
    { kind: 'clarification', input: { ...input, prompt: '😀'.repeat(500), size: 32 }, answer: '😀'.repeat(501) },
    { kind: 'input', prompt: '😀'.repeat(501), size: 32, clarificationUsed: true },
    { kind: 'generation', input, key: 'answer-key', clarificationUsed: true, manualRetry: true, retryAt: 5000 },
  ];
  for (const flow of values) assert.deepEqual(restoreFlow(encodeFlow(flow, 1000), 1500), { flow });
});

test('invalid clarification metadata or generated request length is rejected safely', () => {
  for (const flow of [
    { kind: 'clarification', input, answer: null },
    { kind: 'clarification', input: { ...input, prompt: '😀'.repeat(501) }, answer: '' },
    { kind: 'generation', input, key: 'key', clarificationUsed: false },
    { kind: 'generation', input, key: 'key', manualRetry: 'yes' },
    { kind: 'generation', input, key: 'key', manualRetry: true, retryAt: -1 },
  ]) assert.ok(restoreFlow(JSON.stringify({ version: 1, savedAt: 1000, flow }), 1500).warning);
});
