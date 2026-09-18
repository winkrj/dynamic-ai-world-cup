import type { Candidate, SelectionEvent, SessionStart, Snapshot } from '../api/types.ts';
import { hasKeys, isRecord, isSessionStart, isText } from './validation.ts';

export type PlayPhase = 'preparation' | 'active' | 'feedback' | 'completed';
export type PlayState = Readonly<{
  formatVersion: 1;
  sessionId: string;
  snapshot: Snapshot;
  events: readonly SelectionEvent[];
  phase: PlayPhase;
  startedAt: number | null;
  deadline: number | null;
}>;
export type Match = Readonly<{
  left: Candidate;
  right: Candidate;
  sequence: number;
  roundSize: number;
  /** One-based position in this round, for display only. */
  roundMatch: number;
  roundTotal: number;
}>;

function freeze<T>(value: T): T {
  if (typeof value === 'object' && value !== null && !Object.isFrozen(value)) {
    Object.values(value).forEach(freeze);
    Object.freeze(value);
  }
  return value;
}

function validTime(now: number): boolean {
  return Number.isSafeInteger(now) && now >= 0 && now <= Number.MAX_SAFE_INTEGER - 7000;
}

export function createPlay(start: SessionStart): PlayState {
  if (!isSessionStart(start)) throw new TypeError('Invalid session snapshot');
  return freeze({ formatVersion: 1, sessionId: start.sessionId, snapshot: structuredClone(start.snapshot),
    events: [], phase: 'preparation', startedAt: null, deadline: null });
}

function matchAt(snapshot: Snapshot, events: readonly SelectionEvent[], sequence: number): Match | null {
  if (sequence >= snapshot.size - 1) return null;
  let round = snapshot.initialOrder;
  let offset = 0;
  while (sequence - offset >= round.length / 2) {
    const matches = round.length / 2;
    round = events.slice(offset, offset + matches).map(event => event.winnerId);
    offset += matches;
  }
  const roundIndex = sequence - offset;
  return {
    left: snapshot.candidates.find(candidate => candidate.id === round[roundIndex * 2])!,
    right: snapshot.candidates.find(candidate => candidate.id === round[roundIndex * 2 + 1])!,
    sequence, roundSize: round.length, roundMatch: roundIndex + 1, roundTotal: round.length / 2,
  };
}

export function currentMatch(state: PlayState): Match | null {
  if (state.phase === 'completed') return null;
  return matchAt(state.snapshot, state.events, state.events.length - (state.phase === 'feedback' ? 1 : 0));
}

export function ready(state: PlayState, now: number): PlayState {
  if (state.phase !== 'preparation') return state;
  if (!validTime(now)) throw new RangeError('Invalid clock');
  return freeze({ ...state, phase: 'active', startedAt: now, deadline: now + state.snapshot.rules.matchDurationMs });
}

/** A random bit is uniform: unlike arbitrary integer modulo, two divides the Uint32 range exactly. */
export function randomSide(): 0 | 1 {
  return (crypto.getRandomValues(new Uint32Array(1))[0] & 1) as 0 | 1;
}

export function decide(state: PlayState, decision: {
  now: number;
  winnerId?: string;
  rng?: () => 0 | 1;
  eventId?: string;
}): PlayState {
  if (state.phase !== 'active') return state;
  if (!validTime(decision.now)) throw new RangeError('Invalid clock');
  const expired = decision.now >= state.deadline!;
  if (!expired && decision.winnerId === undefined) return state;
  const match = currentMatch(state)!;
  let winnerId = decision.winnerId;
  if (expired) {
    const side = (decision.rng ?? randomSide)();
    if (side !== 0 && side !== 1) throw new RangeError('Invalid random bit');
    winnerId = side === 0 ? match.left.id : match.right.id;
  } else if (winnerId !== match.left.id && winnerId !== match.right.id) {
    throw new RangeError('Winner must belong to the current match');
  }
  const eventId = decision.eventId ?? crypto.randomUUID();
  if (!isText(eventId) || state.events.some(event => event.eventId === eventId)) throw new RangeError('Invalid event identity');
  const event: SelectionEvent = {
    eventId, sequence: state.events.length, winnerId: winnerId!,
    reason: expired ? 'TIMEOUT_RANDOM' : 'USER_SELECTED',
    elapsedMs: Math.max(0, decision.now - state.startedAt!),
  };
  return freeze({ ...state, phase: 'feedback', events: [...state.events, event] });
}

/** The caller advances only while visible, after the brief selection feedback. */
export function advance(state: PlayState): PlayState {
  if (state.phase !== 'feedback') return state;
  return freeze({ ...state, phase: state.events.length === state.snapshot.size - 1 ? 'completed' : 'preparation', startedAt: null, deadline: null });
}

export function champion(state: PlayState): Candidate | null {
  if (state.events.length !== state.snapshot.size - 1) return null;
  return state.snapshot.candidates.find(candidate => candidate.id === state.events.at(-1)!.winnerId) ?? null;
}

export function serializePlay(state: PlayState): string {
  return JSON.stringify(state);
}

/** Reject corrupted storage rather than sending fabricated/mismatched decisions to the server. */
export function restorePlay(input: unknown): PlayState | null {
  try {
    const value: unknown = typeof input === 'string' && input.length <= 1_000_000 ? JSON.parse(input) : input;
    if (!isRecord(value) || !hasKeys(value, ['formatVersion', 'sessionId', 'snapshot', 'events', 'phase', 'startedAt', 'deadline']) ||
      value.formatVersion !== 1 || !isSessionStart({ sessionId: value.sessionId, snapshot: value.snapshot }) ||
      !Array.isArray(value.events)) return null;
    const snapshot = value.snapshot as Snapshot;
    if (value.events.length > snapshot.size - 1) return null;
    const events: SelectionEvent[] = [];
    const ids = new Set<string>();
    for (const event of value.events) {
      if (!isRecord(event) || !hasKeys(event, ['eventId', 'sequence', 'winnerId', 'reason', 'elapsedMs']) ||
        !isText(event.eventId) || ids.has(event.eventId) || event.sequence !== events.length ||
        !isText(event.winnerId) || !Number.isSafeInteger(event.elapsedMs) || (event.elapsedMs as number) < 0 ||
        (event.reason !== 'USER_SELECTED' && event.reason !== 'TIMEOUT_RANDOM') ||
        ((event.elapsedMs as number) >= 7000) !== (event.reason === 'TIMEOUT_RANDOM')) return null;
      const pair = matchAt(snapshot, events, events.length)!;
      if (event.winnerId !== pair.left.id && event.winnerId !== pair.right.id) return null;
      events.push(event as SelectionEvent);
      ids.add(event.eventId);
    }
    const complete = events.length === snapshot.size - 1;
    if (value.phase === 'active' || value.phase === 'feedback') {
      if (!validTime(value.startedAt as number) || value.deadline !== (value.startedAt as number) + 7000 ||
        (value.phase === 'active' && complete) || (value.phase === 'feedback' && events.length === 0)) return null;
    } else if (value.phase === 'preparation' || value.phase === 'completed') {
      if (value.startedAt !== null || value.deadline !== null || complete !== (value.phase === 'completed')) return null;
    } else return null;
    return freeze(structuredClone(value) as PlayState);
  } catch { return null; }
}
