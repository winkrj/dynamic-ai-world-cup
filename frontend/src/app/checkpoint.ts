import type { GenerationRequest, Preview, SelectionAck, SelectionEvent, ShareCreated, SharedBracket, Size } from '../api/types.ts';
import { restorePlay } from '../play/core.ts';
import { isPreview } from '../api/client.ts';
import type { PlayState } from '../play/core.ts';

export type InputFlow = { kind: 'input' | 'size'; prompt: string; size: Size };
export type GenerationFlow = {
  kind: 'generation'; input: GenerationRequest; key: string; jobId?: string;
  previous?: Preview; failed?: boolean; status?: 'QUEUED' | 'RUNNING';
};
export type PreviewFlow = { kind: 'preview'; input: GenerationRequest; preview: Preview; startKey?: string };
export type PlayFlow = {
  kind: 'play'; play: PlayState; acknowledged: number; ack?: SelectionAck;
  pending?: { key: string; events: SelectionEvent[] };
  shareKey?: string; share?: ShareCreated; feedbackUntil?: number;
};
export type ShareFlow = { kind: 'share'; token: string; shared?: SharedBracket; replayKey?: string };
export type Flow = InputFlow | GenerationFlow | PreviewFlow | PlayFlow | ShareFlow;
type RecordValue = Record<string, unknown>;
const record = (value: unknown): value is RecordValue => !!value && typeof value === 'object' && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0;
const size = (value: unknown): value is Size => value === 8 || value === 16 || value === 32;
const input = (value: unknown): value is GenerationRequest => record(value) && text(value.prompt)
  && value.prompt.trim().length > 0 && value.prompt.length <= 500 && size(value.size)
  && value.locale === 'ko-KR' && text(value.timezone);

function preview(value: unknown): value is Preview {
  return isPreview(value);
}

export function restoreFlow(raw: string | null, now: number): { flow?: Flow; warning?: string } {
  if (!raw) return {};
  try {
    const envelope: unknown = JSON.parse(raw);
    if (!record(envelope) || envelope.version !== 1 || typeof envelope.savedAt !== 'number' || !record(envelope.flow)) throw Error();
    const value = envelope.flow;
    const ttl = value.kind === 'play' ? 30 * 86400000 : 86400000;
    if (now - envelope.savedAt > ttl || envelope.savedAt > now + 60000) throw Error();
    if ((value.kind === 'input' || value.kind === 'size') && typeof value.prompt === 'string'
      && value.prompt.length <= 500 && size(value.size)) return { flow: value as InputFlow };
    if (value.kind === 'generation' && input(value.input) && text(value.key)
      && (value.jobId === undefined || text(value.jobId)) && (value.previous === undefined || preview(value.previous))
      && (value.failed === undefined || typeof value.failed === 'boolean')
      && (value.status === undefined || value.status === 'QUEUED' || value.status === 'RUNNING')) return { flow: value as GenerationFlow };
    if (value.kind === 'preview' && input(value.input) && preview(value.preview)
      && (value.startKey === undefined || text(value.startKey))) return { flow: value as PreviewFlow };
    if (value.kind === 'share' && text(value.token) && (value.replayKey === undefined || text(value.replayKey))) {
      // Fetch public data again; an untrusted cached champion must never be displayed as verified.
      return { flow: { kind: 'share', token: value.token, replayKey: value.replayKey } };
    }
    if (value.kind === 'play') {
      const play = restorePlay(value.play);
      if (!play || !Number.isInteger(value.acknowledged) || Number(value.acknowledged) < 0
        || Number(value.acknowledged) > play.events.length) throw Error();
      if (value.pending !== undefined) {
        if (!record(value.pending) || !text(value.pending.key) || !Array.isArray(value.pending.events)
          || !value.pending.events.length || value.pending.events.some((event: unknown) => !record(event)
            || JSON.stringify(event) !== JSON.stringify(play.events[Number(event.sequence)]))) throw Error();
        const events = value.pending.events as SelectionEvent[];
        if (events.some((event, index) => index > 0 && event.sequence !== events[index - 1].sequence + 1)) throw Error();
      }
      if (value.shareKey !== undefined && !text(value.shareKey)) throw Error();
      if (value.feedbackUntil !== undefined && (typeof value.feedbackUntil !== 'number' || !Number.isFinite(value.feedbackUntil))) throw Error();
      // ACK/share are server claims. Confirm via exact, idempotent final upload before enabling sharing after reload.
      return { flow: { kind: 'play', play, acknowledged: Number(value.acknowledged),
        pending: value.pending as PlayFlow['pending'], shareKey: value.shareKey as string | undefined,
        feedbackUntil: value.feedbackUntil as number | undefined } };
    }
    throw Error();
  } catch {
    return { warning: '저장된 진행을 안전하게 복원할 수 없어요. 이전 경기를 처음부터 다시 진행하지 않습니다. 새 월드컵을 만들어 주세요.' };
  }
}

export function encodeFlow(flow: Flow, now: number): string {
  return JSON.stringify({ version: 1, savedAt: now, flow });
}
