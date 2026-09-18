import test from 'node:test';
import assert from 'node:assert/strict';
import { advance, champion, createPlay, currentMatch, decide, randomSide, ready, restorePlay, serializePlay } from '../src/play/core.ts';
import { isPreview, isSessionStart, isSharedBracket, isSnapshot } from '../src/play/validation.ts';
import type { SessionStart, Size } from '../src/api/types.ts';

function session(size: Size = 8): SessionStart {
  const candidates = Array.from({ length: size }, (_, index) => ({ id: `c${index}`, name: `후보 ${index + 1}`, tags: ['합성 예제'], imageUrl: null }));
  return { sessionId: `session-${size}`, snapshot: { snapshotId: `snapshot-${size}`, schemaVersion: 1, title: '테스트 월드컵', size,
    candidates, initialOrder: candidates.map(candidate => candidate.id).reverse(),
    rules: { matchDurationMs: 7000, timeoutMode: 'UNIFORM_RANDOM', undoAllowed: false }, frozenAt: '2026-09-18T00:00:00Z' } };
}

for (const size of [8, 16, 32] as const) {
  test(`${size}: fixed initial order, winner propagation, N−1 matches and identical final`, () => {
    const original = session(size);
    let state = createPlay(original);
    const snapshot = structuredClone(original.snapshot);
    assert.equal(currentMatch(state)!.left.id, `c${size - 1}`);
    assert.equal(currentMatch(state)!.right.id, `c${size - 2}`);
    const observedRounds: number[] = [];
    for (let sequence = 0; sequence < size - 1; sequence++) {
      assert.equal(state.phase, 'preparation');
      assert.equal(state.deadline, null);
      assert.equal(decide(state, { now: 1000, winnerId: 'c0' }), state);
      const match = currentMatch(state)!;
      observedRounds.push(match.roundSize);
      assert.equal(match.sequence, sequence);
      state = ready(state, 100_000 + sequence * 10_000);
      assert.equal(state.deadline! - state.startedAt!, 7000);
      const chosen = match.left.id;
      state = decide(state, { now: state.startedAt! + 6999, winnerId: chosen, eventId: `event-${sequence}` });
      assert.equal(state.events.at(-1)!.reason, 'USER_SELECTED');
      assert.equal(currentMatch(state)!.left.id, chosen);
      assert.equal(state.phase, 'feedback');
      state = advance(state);
    }
    assert.equal(state.phase, 'completed');
    assert.equal(state.events.length, size - 1);
    assert.equal(currentMatch(state), null);
    assert.equal(champion(state)!.id, `c${size - 1}`);
    assert.equal(observedRounds.at(-1), 2);
    assert.deepEqual(state.snapshot, snapshot);
    assert.equal(ready(state, 200_000), state);
    assert.equal(advance(state), state);
    assert.equal(decide(state, { now: 300_000 }), state);
    assert.deepEqual(restorePlay(serializePlay(state)), state);
  });
}

test('deadline starts at readiness, 6999ms accepts direct, 7000ms ignores clicked candidate', () => {
  const preparing = createPlay(session());
  assert.equal(preparing.startedAt, null);
  const active = ready(preparing, 60_000);
  assert.equal(ready(active, 100_000), active);
  assert.equal(decide(active, { now: 66_999 }), active);
  const pair = currentMatch(active)!;
  const direct = decide(active, { now: 66_999, winnerId: pair.left.id, eventId: 'direct', rng: () => { throw Error('No RNG for direct'); } });
  assert.equal(direct.events[0].elapsedMs, 6999);
  assert.equal(direct.events[0].reason, 'USER_SELECTED');
  const timeout = decide(active, { now: 67_000, winnerId: pair.left.id, eventId: 'timeout', rng: () => 1 });
  assert.equal(timeout.events[0].winnerId, pair.right.id);
  assert.equal(timeout.events[0].elapsedMs, 7000);
  assert.equal(timeout.events[0].reason, 'TIMEOUT_RANDOM');
});

for (const side of [0, 1] as const) {
  test(`timeout can choose side ${side}, repeat tap/tick never adds a second event`, () => {
    let state = ready(createPlay(session()), 1000);
    const pair = currentMatch(state)!;
    let calls = 0;
    state = decide(state, { now: 8000, eventId: 'one-event', rng: () => { calls++; return side; } });
    assert.equal(state.events[0].winnerId, side === 0 ? pair.left.id : pair.right.id);
    const again = decide(state, { now: 8001, winnerId: pair.left.id, rng: () => { calls++; return 1; } });
    assert.equal(again, state);
    assert.equal(again.events.length, 1);
    assert.equal(calls, 1);
  });
}

test('refresh restores exact active deadline; late visibility return expires only current match', () => {
  let state = ready(createPlay(session(32)), 1000);
  state = restorePlay(serializePlay(state))!;
  assert.equal(state.deadline, 8000);
  state = decide(state, { now: 100_000_000, rng: () => 0, eventId: 'late' });
  assert.equal(state.phase, 'feedback');
  assert.equal(state.events.length, 1);
  assert.equal(state.events[0].elapsedMs, 99_999_000);
  assert.deepEqual(restorePlay(serializePlay(state)), state);
  const preparing = advance(state);
  assert.equal(preparing.deadline, null);
  assert.equal(decide(preparing, { now: 100_010_000 }), preparing);
  const active = ready(preparing, 100_020_000);
  assert.equal(active.deadline, 100_027_000);
});

test('snapshot and events are immutable copies; no mutation or Undo operation is exposed', () => {
  const input = session();
  const state = createPlay(input);
  input.snapshot.candidates[0].name = 'changed outside';
  input.snapshot.initialOrder.reverse();
  assert.equal(state.snapshot.candidates[0].name, '후보 1');
  assert.equal(currentMatch(state)!.left.id, 'c7');
  assert.throws(() => { state.snapshot.candidates[0].name = 'unsafe'; }, TypeError);
  assert.throws(() => { state.snapshot.initialOrder.reverse(); }, TypeError);
  const selected = decide(ready(state, 100), { now: 200, winnerId: 'c7', eventId: 'event' });
  assert.equal(state.events.length, 0);
  assert.throws(() => { selected.events[0].winnerId = 'c6'; }, TypeError);
});

test('restore rejects malformed phases, mismatched events, duplicates and rule tampering', () => {
  const active = ready(createPlay(session()), 1000);
  const first = decide(active, { now: 2000, winnerId: 'c7', eventId: 'one' });
  const second = decide(ready(advance(first), 3000), { now: 4000, winnerId: 'c5', eventId: 'two' });
  const mutations: ((value: any) => void)[] = [
    value => { value.snapshot.initialOrder[1] = value.snapshot.initialOrder[0]; },
    value => { value.snapshot.rules.matchDurationMs = 8000; },
    value => { value.snapshot.rules.undoAllowed = true; },
    value => { value.snapshot.candidates[0].imageUrl = 'javascript:alert(1)'; },
    value => { value.events[1].winnerId = 'c7'; },
    value => { value.events[1].eventId = 'one'; },
    value => { value.events[1].sequence = 0; },
    value => { value.events[1].elapsedMs = 7000; },
    value => { value.events[1].reason = 'TIMEOUT_RANDOM'; },
    value => { value.deadline = 9999; },
    value => { value.startedAt = -1; },
    value => { value.phase = 'completed'; value.startedAt = null; value.deadline = null; },
    value => { value.phase = 'unknown'; },
    value => { value.extraPrivateData = 'unrecognized'; },
  ];
  for (const mutate of mutations) {
    const invalid = JSON.parse(serializePlay(second));
    mutate(invalid);
    assert.equal(restorePlay(invalid), null);
  }
  assert.equal(restorePlay('{'), null);
  assert.equal(restorePlay(null), null);
  assert.equal(restorePlay({ ...active, phase: 'feedback' }), null);
});

test('public snapshot/preview/session/share guards reject mismatched cardinality and champion', () => {
  const start = session();
  assert.equal(isSnapshot(start.snapshot), true);
  assert.equal(isSessionStart(start), true);
  assert.equal(isSharedBracket({ snapshot: start.snapshot, championId: 'c0' }), true);
  assert.equal(isSharedBracket({ snapshot: start.snapshot, championId: 'outside' }), false);
  const preview = { draftId: 'draft', version: 1, status: 'READY', size: 8, candidateUnit: '취미', candidates: start.snapshot.candidates, regenerationsRemaining: 1 };
  assert.equal(isPreview(preview), true);
  assert.equal(isPreview({ ...preview, size: 16 }), false);
  assert.equal(isPreview({ ...preview, candidates: [...preview.candidates.slice(1), preview.candidates[1]] }), false);
  assert.equal(isPreview({ ...preview, regenerationsRemaining: 2 }), false);
  const replay = createPlay({ ...start, sessionId: 'another-session' });
  assert.deepEqual(replay.snapshot, start.snapshot);
  assert.equal(replay.events.length, 0);
  assert.equal(replay.sessionId, 'another-session');
});

test('invalid choice/clock/RNG never produces an event', () => {
  const state = ready(createPlay(session()), 1000);
  assert.throws(() => decide(state, { now: 1100, winnerId: 'c0' }));
  assert.throws(() => decide(state, { now: NaN, winnerId: 'c7' }));
  assert.throws(() => decide(state, { now: 8000, rng: (() => 2) as unknown as () => 0 }));
  assert.throws(() => ready(createPlay(session()), Infinity));
  assert.throws(() => createPlay({ ...session(), snapshot: { ...session().snapshot, size: 4 } } as unknown as SessionStart));
  assert.equal(state.events.length, 0);
  for (let i = 0; i < 64; i++) assert.ok([0, 1].includes(randomSide()));
});

test('crypto RNG maps even and odd words to both sides without modulo bias', context => {
  const words = [0, 1, 0xfffffffe, 0xffffffff];
  const mock = context.mock.method(crypto, 'getRandomValues', (array: Uint32Array) => {
    array[0] = words.shift()!;
    return array;
  });
  assert.deepEqual([randomSide(), randomSide(), randomSide(), randomSide()], [0, 1, 0, 1]);
  mock.mock.restore();
});
