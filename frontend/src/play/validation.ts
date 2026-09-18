import type { Candidate, Preview, SessionStart, SharedBracket, Size, Snapshot } from '../api/types.ts';

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function hasKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}

export function isText(value: unknown, max = Infinity): value is string {
  return typeof value === 'string' && value.trim().length > 0 && Array.from(value).length <= max;
}

export function isSize(value: unknown): value is Size {
  return value === 8 || value === 16 || value === 32;
}

export function isHttpUrl(value: unknown, httpsOnly = false): value is string {
  if (!isText(value)) return false;
  try {
    const url = new URL(value);
    return !url.username && !url.password && (url.protocol === 'https:' || (!httpsOnly && url.protocol === 'http:'));
  } catch { return false; }
}

function isCandidate(value: unknown): value is Candidate {
  return isRecord(value) && hasKeys(value, ['id', 'name', 'tags', 'imageUrl']) &&
    isText(value.id) && isText(value.name, 100) && Array.isArray(value.tags) &&
    value.tags.length <= 2 && value.tags.every(tag => isText(tag, 40)) &&
    (value.imageUrl === null || isHttpUrl(value.imageUrl, true));
}

function isCandidateSet(value: unknown, size: Size): value is Candidate[] {
  return Array.isArray(value) && value.length === size && value.every(isCandidate) &&
    new Set(value.map(candidate => candidate.id)).size === size;
}

export function isPreview(value: unknown): value is Preview {
  return isRecord(value) && hasKeys(value, ['draftId', 'version', 'status', 'size', 'candidateUnit', 'candidates', 'regenerationsRemaining']) &&
    isText(value.draftId) && Number.isSafeInteger(value.version) && (value.version as number) >= 1 &&
    value.status === 'READY' && isSize(value.size) && isText(value.candidateUnit) &&
    isCandidateSet(value.candidates, value.size) && (value.regenerationsRemaining === 0 || value.regenerationsRemaining === 1);
}

export function isSnapshot(value: unknown): value is Snapshot {
  if (!isRecord(value) || !hasKeys(value, ['snapshotId', 'schemaVersion', 'title', 'size', 'candidates', 'initialOrder', 'rules', 'frozenAt']) ||
    !isText(value.snapshotId) || value.schemaVersion !== 1 || !isText(value.title, 100) || !isSize(value.size) ||
    !isCandidateSet(value.candidates, value.size) || !Array.isArray(value.initialOrder) ||
    value.initialOrder.length !== value.size || !value.initialOrder.every(id => isText(id)) ||
    new Set(value.initialOrder).size !== value.size || !isRecord(value.rules) ||
    !hasKeys(value.rules, ['matchDurationMs', 'timeoutMode', 'undoAllowed']) ||
    value.rules.matchDurationMs !== 7000 || value.rules.timeoutMode !== 'UNIFORM_RANDOM' || value.rules.undoAllowed !== false ||
    !isText(value.frozenAt) || !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value.frozenAt) || !Number.isFinite(Date.parse(value.frozenAt))) return false;
  const ids = new Set(value.candidates.map(candidate => candidate.id));
  return value.initialOrder.every(id => ids.has(id));
}

export function isSessionStart(value: unknown): value is SessionStart {
  return isRecord(value) && hasKeys(value, ['sessionId', 'snapshot']) && isText(value.sessionId) && isSnapshot(value.snapshot);
}

export function isSharedBracket(value: unknown): value is SharedBracket {
  return isRecord(value) && hasKeys(value, ['snapshot', 'championId']) && isSnapshot(value.snapshot) &&
    isText(value.championId) && value.snapshot.candidates.some(candidate => candidate.id === value.championId);
}
