import type { ApiError, GenerationJob, GenerationRequest, Preview, SelectionAck, SelectionEvent, SessionStart, ShareCreated, SharedBracket } from './types.ts';
import { hasKeys, isHttpUrl, isPreview, isRecord, isSessionStart, isSharedBracket, isText } from '../play/validation.ts';

export { isPreview, isSnapshot, isSessionStart, isSharedBracket } from '../play/validation.ts';

export type FailureCode = ApiError['code'] | 'NETWORK_ERROR' | 'REQUEST_TIMEOUT' | 'ABORTED' | 'INVALID_RESPONSE';
const messages: Record<FailureCode, string> = {
  INVALID_INPUT: '입력한 고민과 조건을 확인해 주세요.',
  NOT_FOUND: '요청한 내용을 찾을 수 없어요. 링크나 저장 기간을 확인해 주세요.',
  VERSION_CONFLICT: '후보 목록이 바뀌었어요. 최신 목록을 다시 확인해 주세요.',
  IDEMPOTENCY_CONFLICT: '이 요청을 이어갈 수 없어요. 현재 상태를 다시 확인해 주세요.',
  OPERATION_IN_PROGRESS: '아직 이전 요청을 처리하고 있어요. 잠시 후 다시 확인해 주세요.',
  REGENERATION_EXHAUSTED: '전체 후보 다시 만들기는 한 번만 가능해요.',
  ALREADY_FROZEN: '이미 시작한 월드컵이에요. 저장된 경기를 이어가 주세요.',
  QUALITY_GATE_FAILED: '조건에 맞는 후보를 충분히 준비하지 못했어요. 고민을 조금 더 구체적으로 적어 주세요.',
  CLARIFICATION_REQUIRED: '비교할 대상을 더 구체적으로 알려 주세요.',
  UNSUPPORTED_REQUEST: '이 조건으로 월드컵을 만들기 어려워요. 다른 고민을 입력해 주세요.',
  RATE_LIMITED: '잠시 쉬었다가 다시 만들어 주세요.',
  PROVIDER_UNAVAILABLE: '지금은 후보를 만들 수 없어요. 잠시 후 다시 시도해 주세요.',
  INVALID_SELECTION: '경기 기록을 저장하지 못했어요. 진행 기록은 이 기기에 보관돼요.',
  SESSION_NOT_COMPLETED: '모든 경기 기록을 저장한 뒤 공유할 수 있어요.',
  INTERNAL_ERROR: '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
  NETWORK_ERROR: '연결을 확인한 뒤 같은 요청을 다시 시도해 주세요.',
  REQUEST_TIMEOUT: '응답을 기다리는 시간이 길어졌어요. 같은 요청으로 다시 확인해 주세요.',
  ABORTED: '요청 대기를 중단했어요.',
  INVALID_RESPONSE: '응답을 안전하게 확인할 수 없어요. 잠시 후 다시 시도해 주세요.',
};

export class ApiFailure extends Error {
  readonly code: FailureCode;
  readonly status: number;
  readonly retryable: boolean;
  readonly retryAt: number | null;
  readonly requestId: string | null;

  constructor(code: FailureCode, options: { status?: number; retryable?: boolean; retryAt?: number | null; requestId?: string | null } = {}) {
    super(messages[code]);
    this.name = 'ApiFailure';
    this.code = code;
    this.status = options.status ?? 0;
    this.retryable = options.retryable ?? false;
    this.retryAt = options.retryAt ?? null;
    this.requestId = options.requestId ?? null;
  }
}

function isApiError(value: unknown): value is ApiError {
  return isRecord(value) && hasKeys(value, ['code', 'message', 'requestId', 'retryable']) &&
    typeof value.code === 'string' && Object.hasOwn(messages, value.code) &&
    !['NETWORK_ERROR', 'REQUEST_TIMEOUT', 'ABORTED', 'INVALID_RESPONSE'].includes(value.code) &&
    isText(value.message) && isText(value.requestId) && typeof value.retryable === 'boolean';
}

export function errorFromJob(error: ApiError): ApiFailure {
  return new ApiFailure(error.code, { retryable: error.retryable, requestId: error.requestId });
}

export function isGenerationJob(value: unknown): value is GenerationJob {
  if (!isRecord(value) || !hasKeys(value, ['jobId', 'status', 'draftId', 'error']) || !isText(value.jobId)) return false;
  if (value.status === 'QUEUED' || value.status === 'RUNNING') return value.draftId === null && value.error === null;
  if (value.status === 'READY') return isText(value.draftId) && value.error === null;
  return value.status === 'FAILED' && value.draftId === null && isApiError(value.error);
}

function isSelectionAck(value: unknown): value is SelectionAck {
  return isRecord(value) && hasKeys(value, ['nextSequence', 'status', 'championId']) &&
    Number.isSafeInteger(value.nextSequence) && (value.nextSequence as number) >= 0 && (value.nextSequence as number) <= 31 &&
    ((value.status === 'PLAYING' && value.championId === null) || (value.status === 'COMPLETED' && isText(value.championId)));
}

function isShareCreated(value: unknown): value is ShareCreated {
  if (!isRecord(value) || !hasKeys(value, ['token', 'url', 'snapshotId', 'championId']) ||
    !isText(value.token) || !isHttpUrl(value.url) || !isText(value.snapshotId) || !isText(value.championId)) return false;
  const url = new URL(value.url);
  return url.pathname === `/shares/${encodeURIComponent(value.token)}` && !url.search && !url.hash;
}

function retryAt(value: string | null, now: number): number | null {
  if (!value) return null;
  if (/^\d+$/.test(value)) {
    const result = now + Number(value) * 1000;
    return Number.isSafeInteger(result) ? result : null;
  }
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? Math.max(now, timestamp) : null;
}

function pathId(id: string): string {
  if (!isText(id)) throw new ApiFailure('INVALID_INPUT');
  return encodeURIComponent(id);
}

export type ApiClient = ReturnType<typeof createApiClient>;

/** Each call is one HTTP attempt. The caller retains operation keys for explicit retries. */
export function createApiClient(fetchImpl: typeof fetch = globalThis.fetch.bind(globalThis), options: {
  timeoutMs?: number;
  now?: () => number;
} = {}) {
  const timeoutMs = options.timeoutMs ?? 15_000;
  const now = options.now ?? Date.now;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 60_000) throw new RangeError('Invalid HTTP timeout');

  async function request<T>(path: string, validate: (value: unknown) => value is T, method: 'GET' | 'POST', key?: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    if (method === 'POST' && (!isText(key, 128) || /[\r\n]/.test(key))) throw new ApiFailure('INVALID_INPUT');
    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort();
    if (signal?.aborted) throw new ApiFailure('ABORTED');
    signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (key !== undefined) headers['Idempotency-Key'] = key;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    try {
      const response = await fetchImpl(`/api/v1${path}`, {
        method, headers, credentials: 'same-origin', cache: 'no-store', redirect: 'error',
        signal: controller.signal, ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      const requestId = response.headers.get('X-Request-Id');
      let value: unknown;
      try { value = await response.json(); } catch {
        if (controller.signal.aborted) throw new ApiFailure(timedOut ? 'REQUEST_TIMEOUT' : 'ABORTED', { retryable: timedOut });
        throw new ApiFailure(response.ok ? 'INVALID_RESPONSE' : 'INTERNAL_ERROR', {
          status: response.status, requestId, retryable: response.status >= 500 || response.status === 429,
          retryAt: retryAt(response.headers.get('Retry-After'), now()),
        });
      }
      if (!response.ok) {
        throw new ApiFailure(isApiError(value) ? value.code : 'INTERNAL_ERROR', {
          status: response.status, requestId: isApiError(value) ? value.requestId : requestId,
          retryable: isApiError(value) ? value.retryable : response.status >= 500 || response.status === 429,
          retryAt: retryAt(response.headers.get('Retry-After'), now()),
        });
      }
      if (!response.headers.get('Content-Type')?.toLowerCase().includes('application/json') || !validate(value)) {
        throw new ApiFailure('INVALID_RESPONSE', { status: response.status, requestId });
      }
      return value;
    } catch (error) {
      if (error instanceof ApiFailure) throw error;
      if (controller.signal.aborted) throw new ApiFailure(timedOut ? 'REQUEST_TIMEOUT' : 'ABORTED', { retryable: timedOut });
      throw new ApiFailure('NETWORK_ERROR', { retryable: true });
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener('abort', abort);
    }
  }

  return {
    createGeneration: (input: GenerationRequest, key: string, signal?: AbortSignal) => request('/generation-jobs', isGenerationJob, 'POST', key, input, signal),
    getJob: (id: string, signal?: AbortSignal) => request(`/generation-jobs/${pathId(id)}`, isGenerationJob, 'GET', undefined, undefined, signal),
    getPreview: (id: string, signal?: AbortSignal): Promise<Preview> => request(`/drafts/${pathId(id)}`, isPreview, 'GET', undefined, undefined, signal),
    regenerate: (id: string, version: number, key: string, signal?: AbortSignal) => request(`/drafts/${pathId(id)}/regenerations`, isGenerationJob, 'POST', key, { expectedVersion: version }, signal),
    start: (id: string, version: number, key: string, signal?: AbortSignal): Promise<SessionStart> => request(`/drafts/${pathId(id)}/start`, isSessionStart, 'POST', key, { expectedVersion: version }, signal),
    selections: (sessionId: string, events: readonly SelectionEvent[], key: string, signal?: AbortSignal) => request(`/sessions/${pathId(sessionId)}/selections`, isSelectionAck, 'POST', key, { events }, signal),
    share: (sessionId: string, key: string, signal?: AbortSignal) => request(`/sessions/${pathId(sessionId)}/shares`, isShareCreated, 'POST', key, undefined, signal),
    getShare: (token: string, signal?: AbortSignal): Promise<SharedBracket> => request(`/shares/${pathId(token)}`, isSharedBracket, 'GET', undefined, undefined, signal),
    replay: (token: string, key: string, signal?: AbortSignal): Promise<SessionStart> => request(`/shares/${pathId(token)}/sessions`, isSessionStart, 'POST', key, undefined, signal),
  };
}
