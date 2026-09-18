import { ApiFailure, createApiClient, errorFromJob } from '../api/client.ts';
import type { GenerationRequest, SelectionAck, Size } from '../api/types.ts';
import { advance, champion, createPlay, currentMatch, decide, ready } from '../play/core.ts';
import { encodeFlow, restoreFlow } from './checkpoint.ts';
import type { Flow, GenerationFlow, PlayFlow } from './checkpoint.ts';
import type { Actions, UserProblem, ViewState } from './view-model.ts';

type Api = ReturnType<typeof createApiClient>;
interface StoragePort { getItem(key: string): string | null; setItem(key: string, value: string): void }
interface Options {
  api: Api; storage: StoragePort; path?: string; now?: () => number; uuid?: () => string;
  visible?: () => boolean; copy?: (text: string) => Promise<void>; navigate?: (path: string) => void;
  pollMs?: number; locked?: boolean;
}

function problem(error: unknown): UserProblem {
  const failure = error instanceof ApiFailure ? error : undefined;
  const messages: Record<string, [string, string]> = {
    QUALITY_GATE_FAILED: ['후보를 충분히 준비하지 못했어요', '조건을 지키면서 비교할 만한 후보가 부족했어요. 고민이나 조건을 조금 더 구체적으로 적어 주세요.'],
    CLARIFICATION_REQUIRED: ['어떤 선택인지 조금 더 알려 주세요', '고르고 싶은 대상과 꼭 필요한 조건을 입력해 주세요.'],
    UNSUPPORTED_REQUEST: ['이 고민은 지금 준비하기 어려워요', '비교해서 고를 수 있는 대상과 조건으로 다시 적어 주세요.'],
    RATE_LIMITED: ['잠시 쉬었다 다시 시도해 주세요', '지금은 새로운 후보를 만들 수 없어요. 대기 후 직접 다시 시도해 주세요.'],
    NOT_FOUND: ['정보를 찾을 수 없어요', '주소가 잘못됐거나 저장 기간이 지났을 수 있어요. 쿠키를 지웠다면 이전의 비공개 진행을 찾을 수 없습니다.'],
    VERSION_CONFLICT: ['후보가 다른 화면에서 바뀌었어요', '최신 후보 전체를 확인한 뒤 다시 시작해 주세요.'],
    REGENERATION_EXHAUSTED: ['전체 다시 만들기를 모두 사용했어요', '현재 후보를 확인하고 시작해 주세요.'],
    OPERATION_IN_PROGRESS: ['앞선 요청을 처리하고 있어요', '잠시 후 같은 요청을 다시 확인해 주세요. 새 생성 요청은 보내지 않습니다.'],
    INVALID_SELECTION: ['선택 기록을 확인해야 해요', '결과는 이 기기에 보관했어요. 기록을 되돌리거나 새 결과로 바꾸지 않습니다.'],
    IDEMPOTENCY_CONFLICT: ['진행 정보가 서로 맞지 않아요', '같은 요청의 내용이 달라 저장하지 못했어요. 이 화면의 진행을 보존합니다.'],
  };
  const [title, detail] = messages[failure?.code ?? ''] ?? ['연결을 확인해 주세요', '진행은 이 기기에 보관했어요. 다시 시도해도 같은 요청과 선택 기록을 사용합니다.'];
  return { title, detail, requestId: failure?.requestId ?? undefined, retryAt: failure?.retryAt ?? undefined };
}

export class AppController {
  private readonly options: Options;
  private readonly storageKey: string;
  private readonly now: () => number;
  private readonly uuid: () => string;
  private flow: Flow;
  private raw: string | null = null;
  private listeners = new Set<() => void>();
  private view!: ViewState;
  private error: UserProblem | null = null;
  private notice: string | null = null;
  private busy = false;
  private saving = false;
  private storageBlocked = false;
  private locked: boolean;
  private stopped = false;
  private timer?: ReturnType<typeof setTimeout>;
  private request?: AbortController;
  private copyBusy = false;
  private uploadFailed = false;

  constructor(options: Options) {
    this.options = options;
    this.now = options.now ?? Date.now;
    this.uuid = options.uuid ?? (() => crypto.randomUUID());
    this.locked = options.locked ?? false;
    const path = options.path ?? '/';
    this.storageKey = `worldcup:flow:v1:${path}`;
    const token = /^\/shares\/([^/]+)$/.exec(path)?.[1];
    let decodedToken = token;
    try { if (token) decodedToken = decodeURIComponent(token); } catch { /* Malformed links resolve to a safe NOT_FOUND response. */ }
    this.flow = decodedToken ? { kind: 'share', token: decodedToken } : { kind: 'input', prompt: '', size: 16 };
    try {
      this.raw = options.storage.getItem(this.storageKey);
      const restored = restoreFlow(this.raw, this.now());
      if (restored.flow) this.flow = restored.flow;
      if (restored.warning) this.notice = restored.warning;
      if (this.flow.kind === 'generation' && this.flow.failed) {
        this.error = {
          title: '후보를 준비하지 못했어요',
          detail: '이전 생성 요청이 실패했어요. 고민을 수정한 뒤 다시 만들어 주세요. 새로고침만으로 후보를 다시 생성하지는 않습니다.',
        };
      }
      // Verify storage before the first mutation: no paid request may precede durable operation state.
      options.storage.setItem(this.storageKey, this.raw ?? encodeFlow(this.flow, this.now()));
      this.raw = options.storage.getItem(this.storageKey);
    } catch { this.failStorage(); }
    this.emit();
  }

  subscribe = (listener: () => void): (() => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener); };
  getSnapshot = (): ViewState => this.view;
  getFlow = (): Flow => this.flow;
  private allowed(): boolean { return !this.locked && !this.storageBlocked && !this.stopped; }
  private visible(): boolean { return this.options.visible?.() ?? true; }
  private failStorage(): void {
    this.storageBlocked = true;
    this.error = { title: '진행을 이 기기에 저장할 수 없어요', detail: '브라우저의 사이트 저장 공간을 허용한 뒤 새로고침해 주세요. 진행이 유실되지 않도록 새로운 요청과 선택을 멈췄어요.' };
  }
  private persist(flow: Flow): boolean {
    if (!this.allowed()) return false;
    try {
      if (this.options.storage.getItem(this.storageKey) !== this.raw) {
        this.locked = true;
        this.notice = '다른 탭에서 진행이 바뀌었어요. 이 탭은 멈췄습니다. 진행 중인 탭을 사용해 주세요.';
        this.emit(); return false;
      }
      const raw = encodeFlow(flow, this.now());
      this.options.storage.setItem(this.storageKey, raw);
      this.raw = raw; this.flow = flow; this.emit(); return true;
    } catch { this.failStorage(); this.emit(); return false; }
  }
  private emit(): void {
    const f = this.flow;
    const play = f.kind === 'play' ? f.play : undefined;
    const localChampion = play ? champion(play) ?? undefined : undefined;
    const preview = f.kind === 'preview' ? f.preview : f.kind === 'generation' ? f.previous : undefined;
    const shared = f.kind === 'share' ? f.shared : undefined;
    const candidates = preview?.candidates ?? play?.snapshot.candidates ?? shared?.snapshot.candidates;
    const saved = f.kind === 'play' && !!localChampion && f.ack?.status === 'COMPLETED'
      && f.ack.championId === localChampion.id && f.ack.nextSequence === play!.snapshot.size - 1;
    const screen = f.kind === 'input' || f.kind === 'size' ? f.kind : f.kind === 'generation'
      ? (f.previous ? 'preview' : 'generating') : f.kind === 'preview' ? 'preview' : f.kind === 'play'
        ? (play!.phase === 'completed' ? 'champion' : 'play') : shared ? 'share' : this.error ? 'share-error' : 'share-loading';
    this.view = {
      screen, prompt: 'prompt' in f ? f.prompt : 'input' in f ? f.input.prompt : '',
      size: 'size' in f ? f.size : 'input' in f ? f.input.size : play?.snapshot.size ?? shared?.snapshot.size ?? 16,
      preview, previewLocked: f.kind === 'generation' || (f.kind === 'preview' && !!f.startKey), play, match: play ? currentMatch(play) : undefined, champion: localChampion, shared,
      shareUrl: f.kind === 'play' ? f.share?.url : undefined,
      generationStatus: f.kind === 'generation' ? f.status : undefined,
      busy: this.busy || this.locked || this.storageBlocked || (f.kind === 'generation' && !f.failed && !this.error),
      saving: this.saving, saved,
      canRetry: !!this.error && !this.busy && !this.saving && this.allowed()
        && (f.kind === 'play' || f.kind === 'share' || (f.kind === 'generation' && !f.failed)
          || (f.kind === 'preview' && !!f.startKey)),
      canEdit: (f.kind === 'generation' && !!f.failed) || (f.kind === 'preview' && !f.startKey),
      storageBlocked: this.storageBlocked, locked: this.locked,
      synthetic: candidates?.some(c => c.name.startsWith('[개발용]')) ?? false,
      homeReturnsToExisting: (this.options.path ?? '/') !== '/',
      now: this.now(), error: this.error, notice: this.notice,
    };
    for (const listener of this.listeners) listener();
  }
  async resume(): Promise<void> {
    if (!this.allowed() || this.busy || this.saving) return;
    if (this.flow.kind === 'generation' && !this.flow.failed) await this.runGeneration();
    else if (this.flow.kind === 'preview' && this.flow.startKey) await this.start();
    else if (this.flow.kind === 'share') await (this.flow.replayKey ? this.replay() : this.loadShare());
    else if (this.flow.kind === 'play') { this.tick(); await this.upload(); }
  }
  dispose(): void { this.stopped = true; clearTimeout(this.timer); this.request?.abort(); this.listeners.clear(); }
  setLocked(locked: boolean): void { this.locked = locked; this.emit(); }
  storageChanged(): void {
    try {
      if (this.options.storage.getItem(this.storageKey) !== this.raw) {
        this.locked = true; this.request?.abort(); clearTimeout(this.timer);
        this.notice = '다른 탭에서 진행이 바뀌었어요. 이 탭에서는 선택을 멈췄습니다.'; this.emit();
      }
    } catch { this.failStorage(); this.emit(); }
  }
  editPrompt(value: string): void {
    if (this.flow.kind === 'input' && this.allowed()) this.persist({ ...this.flow, prompt: value.slice(0, 500) });
  }
  chooseSize(size: Size): void {
    if (this.flow.kind === 'size' && [8, 16, 32].includes(size)) this.persist({ ...this.flow, size });
  }
  next(): void {
    if (this.flow.kind !== 'input' || !this.flow.prompt.trim()) return;
    this.error = null; this.persist({ ...this.flow, kind: 'size' });
  }
  back(): void { if (this.flow.kind === 'size') this.persist({ ...this.flow, kind: 'input' }); }
  async generate(): Promise<void> {
    if (this.flow.kind !== 'size' || !this.flow.prompt.trim() || this.busy || !this.allowed()) return;
    const input: GenerationRequest = { prompt: this.flow.prompt.trim(), size: this.flow.size, locale: 'ko-KR', timezone: 'Asia/Seoul' };
    this.error = null; this.notice = null;
    if (this.persist({ kind: 'generation', input, key: this.uuid() })) await this.runGeneration();
  }
  async regenerate(): Promise<void> {
    if (this.flow.kind !== 'preview' || this.flow.startKey || this.flow.preview.regenerationsRemaining !== 1 || this.busy || !this.allowed()) return;
    if (this.error?.retryAt && this.now() < this.error.retryAt) return;
    this.error = null;
    if (this.persist({ kind: 'generation', input: this.flow.input, previous: this.flow.preview, key: this.uuid() })) await this.runGeneration();
  }
  private async runGeneration(): Promise<void> {
    if (this.flow.kind !== 'generation' || this.flow.failed || !this.allowed() || this.busy) return;
    this.busy = true; this.error = null; clearTimeout(this.timer);
    this.request = new AbortController(); this.emit();
    try {
      let f: GenerationFlow = this.flow;
      if (!f.jobId) {
        const result = f.previous
          ? await this.options.api.regenerate(f.previous.draftId, f.previous.version, f.key, this.request.signal)
          : await this.options.api.createGeneration(f.input, f.key, this.request.signal);
        f = { ...f, jobId: result.jobId };
        if (!this.persist(f)) return;
      }
      const job = await this.options.api.getJob(f.jobId!, this.request.signal);
      if (job.status === 'READY' && job.draftId) {
        const preview = await this.options.api.getPreview(job.draftId, this.request.signal);
        this.persist({ kind: 'preview', input: f.input, preview });
      } else if (job.status === 'FAILED') {
        this.error = problem(job.error ? errorFromJob(job.error) : new Error());
        if (f.previous) this.persist({ kind: 'preview', input: f.input, preview: f.previous });
        else this.persist({ ...f, failed: true });
      } else {
        this.persist({ ...f, status: job.status === 'RUNNING' ? 'RUNNING' : 'QUEUED' });
        this.timer = setTimeout(() => { void this.runGeneration(); }, this.options.pollMs ?? 1000);
      }
    } catch (error) {
      if (!this.stopped) this.error = problem(error);
      if (error instanceof ApiFailure && ['VERSION_CONFLICT', 'REGENERATION_EXHAUSTED', 'INVALID_INPUT', 'NOT_FOUND', 'RATE_LIMITED'].includes(error.code)
        && this.flow.kind === 'generation' && !this.flow.jobId) {
        const f = this.flow;
        if (f.previous) {
          try {
            const preview = await this.options.api.getPreview(f.previous.draftId);
            this.persist({ kind: 'preview', input: f.input, preview });
          } catch { this.persist({ kind: 'preview', input: f.input, preview: f.previous }); }
        } else if (error.code !== 'RATE_LIMITED') this.persist({ ...f, failed: true });
      } else if (error instanceof ApiFailure && error.code === 'NOT_FOUND' && this.flow.kind === 'generation') {
        this.persist({ ...this.flow, failed: true });
      }
    }
    finally { this.busy = false; this.emit(); }
  }
  async start(): Promise<void> {
    if (this.flow.kind !== 'preview' || !this.allowed() || this.busy) return;
    const f = { ...this.flow, startKey: this.flow.startKey ?? this.uuid() };
    if (!this.persist(f)) return;
    this.busy = true; this.error = null; this.emit();
    try {
      const session = await this.options.api.start(f.preview.draftId, f.preview.version, f.startKey);
      this.persist({ kind: 'play', play: createPlay(session), acknowledged: 0 });
    } catch (error) {
      this.error = problem(error);
      if (error instanceof ApiFailure && ['VERSION_CONFLICT', 'REGENERATION_EXHAUSTED'].includes(error.code)) {
        try { const preview = await this.options.api.getPreview(f.preview.draftId); this.persist({ kind: 'preview', input: f.input, preview }); }
        catch { /* Keep the original start operation when reconciliation is unavailable. */ }
      } else if (error instanceof ApiFailure && error.code === 'NOT_FOUND') {
        this.persist({ kind: 'preview', input: f.input, preview: f.preview });
      }
    } finally { this.busy = false; this.emit(); }
  }
  ready(sequence: number): void {
    if (!this.allowed() || !this.visible() || this.flow.kind !== 'play' || currentMatch(this.flow.play)?.sequence !== sequence) return;
    const next = ready(this.flow.play, this.now());
    if (next !== this.flow.play) this.persist({ ...this.flow, play: next });
  }
  choose(id: string): void { this.resolveChoice(id); }
  private resolveChoice(id?: string): void {
    if (!this.allowed() || !this.visible() || this.flow.kind !== 'play') return;
    const match = currentMatch(this.flow.play);
    if (id !== undefined && id !== match?.left.id && id !== match?.right.id) return;
    const next = decide(this.flow.play, { now: this.now(), winnerId: id, eventId: this.uuid() });
    if (next !== this.flow.play && this.persist({ ...this.flow, play: next, feedbackUntil: this.now() + 280 })) void this.upload();
  }
  tick(): void {
    if (this.allowed() && this.visible() && this.flow.kind === 'play') {
      if (this.flow.play.phase === 'active') this.resolveChoice();
      else if (this.flow.play.phase === 'feedback' && this.now() >= (this.flow.feedbackUntil ?? 0)) {
        this.persist({ ...this.flow, play: advance(this.flow.play), feedbackUntil: undefined });
      }
    }
    this.emit();
  }
  private validAck(ack: SelectionAck, f: PlayFlow): boolean {
    if (ack.nextSequence < f.acknowledged || ack.nextSequence > f.play.events.length) return false;
    if (ack.status === 'COMPLETED') {
      const final = f.play.events.at(-1);
      return f.play.events.length === f.play.snapshot.size - 1 && ack.nextSequence === f.play.events.length && ack.championId === final?.winnerId;
    }
    return ack.nextSequence < f.play.snapshot.size - 1 && ack.championId === null;
  }
  private async upload(): Promise<void> {
    if (!this.allowed() || this.saving || this.uploadFailed || this.flow.kind !== 'play' || !this.flow.play.events.length) return;
    let f = this.flow;
    // After reload, re-confirm the final stored result with an identical event prefix.
    if (!f.pending && (f.acknowledged < f.play.events.length || (!f.ack && f.play.events.length === f.play.snapshot.size - 1))) {
      const events = f.acknowledged === f.play.events.length ? f.play.events : f.play.events.slice(f.acknowledged);
      f = { ...f, pending: { key: this.uuid(), events: [...events] } };
      if (!this.persist(f)) return;
    }
    const pending = f.pending;
    if (!pending) return;
    this.saving = true; this.emit();
    let continueQueue = false;
    try {
      const ack = await this.options.api.selections(f.play.sessionId, pending.events, pending.key);
      if (this.flow.kind !== 'play' || this.flow.play.sessionId !== f.play.sessionId) return;
      const current = this.flow;
      if (!this.validAck(ack, current) || ack.nextSequence < pending.events.at(-1)!.sequence + 1) throw Error('Invalid selection acknowledgement');
      this.error = null;
      if (this.persist({ ...current, acknowledged: ack.nextSequence, ack, pending: undefined })) continueQueue = ack.nextSequence < current.play.events.length;
    } catch (error) { this.uploadFailed = true; if (!this.stopped) this.error = problem(error); }
    finally { this.saving = false; this.emit(); }
    if (continueQueue) await this.upload();
  }
  async share(): Promise<void> {
    if (this.flow.kind !== 'play' || !this.view.saved || this.busy || !this.allowed()) return;
    if (this.flow.share) return;
    const f = { ...this.flow, shareKey: this.flow.shareKey ?? this.uuid() };
    if (!this.persist(f)) return;
    this.busy = true; this.error = null; this.emit();
    try {
      const share = await this.options.api.share(f.play.sessionId, f.shareKey);
      const url = new URL(share.url);
      if (!['https:', 'http:'].includes(url.protocol) || share.snapshotId !== f.play.snapshot.snapshotId
        || share.championId !== f.play.events.at(-1)?.winnerId) throw Error('Invalid shared result');
      this.persist({ ...f, share });
    } catch (error) { this.error = problem(error); }
    finally { this.busy = false; this.emit(); }
  }
  async copyShare(): Promise<void> {
    if (this.flow.kind !== 'play' || !this.flow.share || this.copyBusy) return;
    this.copyBusy = true;
    try {
      if (!this.options.copy) throw Error('Clipboard unavailable');
      await this.options.copy(this.flow.share.url); this.notice = '공유 주소를 복사했어요.';
    }
    catch { this.notice = '자동 복사가 안 되었어요. 아래 주소를 직접 선택해 복사해 주세요.'; }
    finally { this.copyBusy = false; this.emit(); }
  }
  private async loadShare(): Promise<void> {
    if (this.flow.kind !== 'share' || this.busy || !this.allowed()) return;
    const f = this.flow; this.busy = true; this.error = null; this.emit();
    try { this.persist({ ...f, shared: await this.options.api.getShare(f.token) }); }
    catch (error) { this.error = problem(error); }
    finally { this.busy = false; this.emit(); }
  }
  async replay(): Promise<void> {
    if (this.flow.kind !== 'share' || this.busy || !this.allowed()) return;
    const f = { ...this.flow, replayKey: this.flow.replayKey ?? this.uuid() };
    if (!this.persist(f)) return;
    this.busy = true; this.error = null; this.emit();
    try {
      const session = await this.options.api.replay(f.token, f.replayKey);
      if (f.shared && JSON.stringify(session.snapshot) !== JSON.stringify(f.shared.snapshot)) throw Error('Shared snapshot changed');
      this.persist({ kind: 'play', play: createPlay(session), acknowledged: 0 });
    } catch (error) { this.error = problem(error); }
    finally { this.busy = false; this.emit(); }
  }
  async retry(): Promise<void> {
    if (!this.allowed() || this.busy || this.saving || (this.error?.retryAt && this.now() < this.error.retryAt)) return;
    this.uploadFailed = false;
    this.error = null;
    if (this.flow.kind === 'play') {
      if (this.view.saved && this.flow.shareKey) await this.share();
      else await this.upload();
    } else await this.resume();
  }
  editInput(): void {
    if (!this.view.canEdit || !('input' in this.flow)) return;
    clearTimeout(this.timer); this.error = null;
    this.persist({ kind: 'input', prompt: this.flow.input.prompt, size: this.flow.input.size });
  }
  newCup(): void {
    if (!this.allowed() || this.busy || this.saving) return;
    if (this.flow.kind === 'play' && (!this.view.saved || this.flow.play.phase !== 'completed')) return;
    if (this.flow.kind === 'generation' && !this.flow.failed) return;
    if ((this.options.path ?? '/') !== '/') { this.options.navigate?.('/'); return; }
    this.error = null; this.notice = null; this.persist({ kind: 'input', prompt: '', size: 16 });
  }
  readonly actions: Actions = {
    editPrompt: value => this.editPrompt(value), chooseSize: value => this.chooseSize(value), next: () => this.next(), back: () => this.back(),
    generate: () => { void this.generate(); }, regenerate: () => { void this.regenerate(); }, start: () => { void this.start(); },
    ready: sequence => this.ready(sequence), choose: id => this.choose(id), retry: () => { void this.retry(); }, editInput: () => this.editInput(),
    newCup: () => this.newCup(), replay: () => { void this.replay(); }, share: () => { void this.share(); }, copyShare: () => { void this.copyShare(); },
  };
}
