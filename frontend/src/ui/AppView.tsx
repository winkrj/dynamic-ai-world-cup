import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import type { Actions, ViewState } from '../app/view-model.ts';
import type { Candidate, Size } from '../api/types.ts';
import { formatRetryDuration } from './retry-duration.ts';
import { clarificationProblem, clarificationQuestion, clarifiedPrompt, promptLength } from '../app/clarification.ts';

type ViewProps = { state: ViewState; actions: Actions };
const examples = [
  '혼자 오래 즐길 취미를 찾고 있어. 운동은 싫고, 한 달 예산은 10만 원이야.',
  '퇴근하고 집에서 30분 정도, 화면을 보지 않고 기분 전환할 일을 찾고 있어.',
  '친구들과 주말에 함께 배워볼 새로운 취미를 고르고 싶어.',
];
const twoDigits = (value: number) => String(value).padStart(2, '0');

function Arrow({ direction = 'right' }: { direction?: 'right' | 'left' }) {
  return <span className="arrow" aria-hidden="true">{direction === 'right' ? '↗' : '←'}</span>;
}

function Heading({ eyebrow, children, subtitle }: { eyebrow: string; children: ReactNode; subtitle?: ReactNode }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus({ preventScroll: true }); }, []);
  return <div className="screen-heading">
    <p className="eyebrow">{eyebrow}</p>
    <h1 ref={heading} tabIndex={-1}>{children}</h1>
    {subtitle && <p className="lede">{subtitle}</p>}
  </div>;
}

function Tags({ candidate }: { candidate: Candidate }) {
  return <span className="tags">{candidate.tags.slice(0, 2).map((tag, index) => <span key={`${tag}-${index}`}>{tag}</span>)}</span>;
}

function GenerationPolicy() {
  return <p className="fine-print generation-policy">짧은 시간에 여러 번 만들면 잠시 기다려야 할 수 있어요.<br />같은 요청 재전송·공유 플레이는 AI를 새로 호출하지 않아요.</p>;
}

/** A missing, failed, or slow image settles to the same-size artwork. */
function CandidateArt({ candidate, number, onSettled }: { candidate: Candidate; number: string; onSettled?: () => void }) {
  const [imageState, setImageState] = useState<'loading' | 'loaded' | 'fallback'>(candidate.imageUrl ? 'loading' : 'fallback');
  const settled = useRef(onSettled);
  settled.current = onSettled;
  useEffect(() => {
    if (imageState !== 'loading') { settled.current?.(); return; }
    const timer = window.setTimeout(() => setImageState('fallback'), 2000);
    return () => window.clearTimeout(timer);
  }, [imageState]);
  return <span className={`candidate-art candidate-art--${imageState}`} aria-hidden="true">
    {imageState !== 'fallback' && candidate.imageUrl && <img src={candidate.imageUrl} alt="" draggable={false}
      onLoad={() => setImageState((current) => current === 'loading' ? 'loaded' : current)} onError={() => setImageState('fallback')} />}
    <span className="art-lines" /><span className="art-number">{number}</span><span className="art-caption">YOUR NEXT PICK</span>
  </span>;
}

function CandidateList({ candidates }: { candidates: Candidate[] }) {
  return <ol className="candidate-list" aria-label={`후보 ${candidates.length}개 전체`}>
    {candidates.map((candidate, index) => <li key={candidate.id} data-testid="candidate-preview">
      <span className="candidate-number">{twoDigits(index + 1)}</span>
      <div className="candidate-summary"><h3>{candidate.name}</h3><Tags candidate={candidate} /></div>
      <span className="list-mark" aria-hidden="true">↗</span>
    </li>)}
  </ol>;
}

function Problems({ state, actions }: ViewProps) {
  const remainingSeconds = state.error?.retryAt ? Math.max(0, Math.ceil((state.error.retryAt - state.now) / 1000)) : 0;
  return <>
    {state.locked && <div className="message message--warning" role="alert"><strong>진행을 안전하게 열 수 없어요.</strong><p>다른 탭이 열려 있다면 그 탭에서 계속해 주세요. 다른 탭이 없다면 최신 브라우저에서 다시 열어 주세요.</p></div>}
    {state.storageBlocked && <div className="message message--warning" role="alert"><strong>진행 상황을 저장할 수 없어요.</strong><p>브라우저의 사이트 저장 공간을 허용한 뒤 다시 시도해 주세요. 안전하게 이어 할 수 있도록 시작을 잠시 멈췄어요.</p></div>}
    {state.error && <section className="message message--error" aria-label="문제 안내">
      <div role="alert"><strong>{state.error.title}</strong><p>{state.error.detail}</p></div>
      {remainingSeconds > 0 && <p className="retry-countdown" aria-live="off">다시 시도까지 {formatRetryDuration(remainingSeconds)}</p>}
      {(state.canRetry || state.canEdit) && <div className="message-actions">
        {state.canRetry && <button className="button button--small" disabled={state.busy || remainingSeconds > 0 || state.locked} onClick={actions.retry}>다시 시도</button>}
        {state.canEdit && <button className="text-button" disabled={state.busy || state.locked} onClick={actions.editInput}>고민 수정하기 <Arrow direction="left" /></button>}
      </div>}
      {state.error.requestId && <details className="support-id"><summary>문의용 정보</summary><code>{state.error.requestId}</code></details>}
    </section>}
    {state.notice && <p className="notice" role="status">{state.notice}</p>}
  </>;
}

function InputScreen({ state, actions }: ViewProps) {
  return <section className="input-layout">
    <div className="input-intro">
      <Heading eyebrow="THINK LESS. PICK ONE." subtitle={<>후보를 만나고, 둘 중 하나씩.<br />마지막에 남는 건 너의 선택.</>}>고민은 짧게.<br />선택은 <span className="accent-text">너답게.</span></Heading>
      <div className="intro-symbol" aria-hidden="true"><span>A</span><b>↗</b><span>B</span></div>
      <p className="tiny-label intro-caption">MANY POSSIBILITIES. ONE CHAMPION.</p>
    </div>
    <form className="prompt-panel" onSubmit={(event) => { event.preventDefault(); actions.next(); }}>
      <div className="section-kicker"><span className="tiny-label">01 / YOUR QUESTION</span><span className="square-dot" /></div>
      <label htmlFor="question">지금, 무엇을 고르고 싶어?</label>
      <textarea id="question" name="question" value={state.prompt} disabled={state.busy || state.locked}
        onChange={(event) => actions.editPrompt(event.target.value)} aria-describedby="question-help question-count"
        aria-invalid={promptLength(state.prompt) > 500}
        placeholder="취향, 예산, 함께할 사람… 원하는 조건을 편하게 적어 주세요." rows={5} required />
      <div className="field-help"><span id="question-help">{promptLength(state.prompt) > 500 ? '입력은 그대로 보관했어요. 500자 이내로 줄여 주세요.' : '조건이 구체적일수록, 더 나다운 후보.'}</span><span id="question-count">{promptLength(state.prompt)} / 500</span></div>
      <button className="button button--primary" type="submit" disabled={!state.prompt.trim() || promptLength(state.prompt) > 500 || state.busy || state.locked || state.storageBlocked}>월드컵 만들기 <Arrow /></button>
      <p className="input-reassurance">로그인 없이 시작해요. 후보는 먼저 확인할 수 있어요.<br />짧은 시간의 반복 요청은 일시적으로 제한될 수 있어요.</p>
      <div className="example-section"><p className="tiny-label">이렇게 시작해 봐도 좋아요</p>
        {examples.map((example, index) => <button className="example" type="button" key={example} disabled={state.busy || state.locked} onClick={() => actions.editPrompt(example)}>
          <span className="example-number">{twoDigits(index + 1)}</span><span>{example}</span><span aria-hidden="true">↗</span>
        </button>)}
      </div>
    </form>
  </section>;
}

function SizeScreen({ state, actions }: ViewProps) {
  const choices: { size: Size; heading: string; description: string }[] = [
    { size: 8, heading: '가볍게 골라볼까', description: '핵심 후보로 빠르게' },
    { size: 16, heading: '딱 좋은 선택의 폭', description: '다양함과 속도 사이' },
    { size: 32, heading: '가능성을 넓혀볼까', description: '더 많은 후보를 탐색' },
  ];
  return <section className="flow-page">
    <Heading eyebrow="02 / CHOOSE YOUR SIZE" subtitle="후보는 전부 미리 보고, 대결은 둘씩 골라요.">얼마나 넓게<br /><span className="accent-text">골라볼까?</span></Heading>
    <p className="prompt-quote">{state.prompt}</p>
    <fieldset className="size-options"><legend className="visually-hidden">월드컵 후보 수</legend>
      {choices.map(({ size, heading, description }) => <label className={`size-option ${state.size === size ? 'is-selected' : ''}`} key={size}>
        <input type="radio" name="size" value={size} checked={state.size === size} onChange={() => actions.chooseSize(size)} disabled={state.busy || state.locked} />
        <span className="size-numeral">{size}<small>강</small></span><span className="size-copy"><strong>{heading}</strong><span>{description}</span></span>
        <span className="size-count">{size - 1}번의 선택</span><span className="radio-mark" aria-hidden="true" />
      </label>)}
    </fieldset>
    <p className="fine-print">대결마다 7초. 시간이 지나면 둘 중 하나가 무작위로 진출해요.</p>
    {state.size === 32 && <p className="fine-print size-quality-note">32강은 비슷한 후보가 섞일 수 있어요. 빠른 선택에는 16강을 추천해요.</p>}
    <GenerationPolicy />
    <div className="action-stack"><button className="button button--primary" disabled={state.busy || state.locked || state.storageBlocked} onClick={actions.generate}>후보 {state.size}개 만들기 <Arrow /></button>
      <button className="text-button" disabled={state.busy || state.locked} onClick={actions.back}><Arrow direction="left" /> 고민으로 돌아가기</button></div>
  </section>;
}

function GeneratingScreen({ state }: ViewProps) {
  const terminalFailure = !!state.error && state.canEdit;
  const paused = !!state.error && !terminalFailure;
  return <section className="flow-page generating-page" aria-busy={!state.error}>
    <Heading eyebrow="03 / FINDING POSSIBILITIES" subtitle={terminalFailure ? '조건을 확인한 뒤 고민을 수정해 다시 시작할 수 있어요.' : paused ? '연결을 다시 확인해 같은 요청을 이어갈 수 있어요.' : '고민과 조건에 맞는 후보를 만들고 확인하고 있어요.'}>
      {terminalFailure ? <>후보를<br /><span className="accent-text">준비하지 못했어요.</span></> : paused ? <>진행 상황을<br /><span className="accent-text">확인해 주세요.</span></> : <>고를 만한 후보를<br /><span className="accent-text">준비하는 중.</span></>}
    </Heading>
    {!state.error && <><div className="loading-deck" aria-hidden="true"><span className="loading-card">?</span><span className="loading-card">?</span><span className="loading-card">?</span></div>
      <div className="loading-line" aria-hidden="true"><span /></div>
      <p className="loading-status" role="status">{state.generationStatus === 'QUEUED' ? '순서가 되면 후보 준비를 시작해요.' : '후보가 준비되면 전체 미리보기로 이동해요.'}</p>
      <p className="fine-print">조금 걸릴 수 있어요. 이 화면에서 기다려 주세요.</p></>}
    <div className="generation-recap"><span className="tiny-label">YOUR WORLD CUP</span><strong>{state.size}강 · {state.size - 1}번의 선택</strong><p>{state.prompt}</p></div>
  </section>;
}

function ClarificationScreen({ state, actions }: ViewProps) {
  const answer = state.clarificationAnswer ?? '';
  const validation = clarificationProblem(state.prompt, answer);
  const count = promptLength(clarifiedPrompt(state.prompt, answer));
  return <section className="flow-page clarification-page">
    <Heading eyebrow="ONE QUICK QUESTION" subtitle="지금은 이 요청의 후보를 준비하기 어려워요. 비교할 대상과 조건을 확인해 주세요. 최신 장소·가격 확인이 필요한 추천은 지원하지 않아요. 처음 적은 조건은 함께 보낼게요.">어떤 대상을<br /><span className="accent-text">고르고 싶나요?</span></Heading>
    <p className="tiny-label">처음 적은 고민 · {state.size}강</p>
    <blockquote className="prompt-quote clarification-original">{state.prompt}</blockquote>
    <form className="prompt-panel" onSubmit={event => { event.preventDefault(); actions.submitClarification(); }}>
      <label htmlFor="clarification-answer">{clarificationQuestion}</label>
      <textarea id="clarification-answer" name="clarification-answer" value={answer} rows={3}
        disabled={state.busy || state.locked || state.storageBlocked} required
        onChange={event => actions.editClarification(event.target.value)}
        aria-describedby="clarification-help clarification-count clarification-cost"
        aria-invalid={!!answer.trim() && !!validation} placeholder="예: 새로 시작할 취미 중 하나를 고르고 싶어요." />
      <div className="field-help"><span id="clarification-help">{validation ?? '처음 고민과 답변을 합쳐 새 요청으로 보냅니다.'}</span><span id="clarification-count">합계 {count} / 500</span></div>
      <p id="clarification-cost" className="fine-print clarification-cost">앞선 요청은 실패로 끝났어요. 답변을 보내면 새 후보 생성을 요청하며, 반복 요청은 일시적으로 제한될 수 있어요.</p>
      <button className="button button--primary" type="submit" disabled={!!validation || state.busy || state.locked || state.storageBlocked}>답변을 더해 후보 {state.size}개 만들기 <Arrow /></button>
      <button className="text-button" type="button" disabled={state.busy || state.locked || state.storageBlocked} onClick={actions.editInput}><Arrow direction="left" /> 처음 고민 수정하기</button>
    </form>
    <GenerationPolicy />
  </section>;
}

function PreviewScreen({ state, actions }: ViewProps) {
  const preview = state.preview;
  if (!preview) return null;
  return <section className="preview-page">
    <Heading eyebrow="04 / MEET THE CONTENDERS" subtitle={<>대결 전에 후보 {preview.size}개를 모두 살펴봐요.<br />시작하면 후보와 대진은 바뀌지 않아요.</>}>이 중에,<br /><span className="accent-text">너의 선택은?</span></Heading>
    <div className="roster-heading"><h2>{preview.candidateUnit} <span>{preview.size}강</span></h2><span className="tiny-label">{twoDigits(preview.candidates.length)} CANDIDATES</span></div>
    <CandidateList candidates={preview.candidates} />
    <p className="fine-print preview-quality-note">후보는 선택을 돕는 추천이에요. 시작 전에 예산·준비물 등 내 조건에 맞는지 확인해 주세요.</p>
    {state.busy && <p className="notice" role="status">처리 중이에요. 완료될 때까지 현재 후보를 그대로 보여드려요.</p>}
    <div className="preview-actions action-stack">
      <button className="button button--primary" onClick={actions.start} disabled={state.previewLocked || state.busy || state.locked || state.storageBlocked}>이대로 시작 <Arrow /></button>
      <GenerationPolicy />
      <button className="button button--outline" onClick={actions.regenerate} disabled={state.previewLocked || state.busy || state.locked || state.storageBlocked || preview.regenerationsRemaining === 0}>전체 다시 만들기 <span className="button-meta">교체 남은 {preview.regenerationsRemaining}회</span></button>
      <p className="fine-print">전체 후보 교체는 성공 기준 1회. 시작 후 되돌리기는 없어요.</p>
    </div>
  </section>;
}

function PlayScreen({ state, actions }: ViewProps) {
  const play = state.play;
  const match = state.match;
  if (!play || !match) return null;
  return <MatchView key={`${play.snapshot.snapshotId}:${match.sequence}`} state={state} actions={actions} />;
}

function MatchView({ state, actions }: ViewProps) {
  const play = state.play!;
  const match = state.match!;
  const [leftReady, setLeftReady] = useState(false);
  const [rightReady, setRightReady] = useState(false);
  const [entered, setEntered] = useState(false);
  // Only preparation can announce a round. Restoring an active match never covers or resets its deadline.
  const [roundAnnounced, setRoundAnnounced] = useState(play.phase !== 'preparation' || match.roundMatch !== 1);
  const [visible, setVisible] = useState(document.visibilityState === 'visible');
  const leftSettled = useCallback(() => setLeftReady(true), []);
  const rightSettled = useCallback(() => setRightReady(true), []);
  const firstCard = useRef<HTMLButtonElement>(null);
  const readyAction = useRef(actions.ready);
  readyAction.current = actions.ready;
  useEffect(() => {
    const timer = window.setTimeout(() => setEntered(true), 350);
    const onVisibility = () => setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisibility);
    return () => { window.clearTimeout(timer); document.removeEventListener('visibilitychange', onVisibility); };
  }, []);
  useEffect(() => {
    if (roundAnnounced || play.phase !== 'preparation' || !visible || state.locked || state.storageBlocked) return;
    // An interrupted announcement restarts when visible/unlocked; no game time has begun yet.
    const timer = window.setTimeout(() => setRoundAnnounced(true), 500);
    return () => window.clearTimeout(timer);
  }, [roundAnnounced, play.phase, visible, state.locked, state.storageBlocked]);
  useEffect(() => {
    if (play.phase === 'preparation' && entered && roundAnnounced && leftReady && rightReady && visible && document.visibilityState === 'visible' && !state.locked && !state.storageBlocked) readyAction.current(match.sequence);
  }, [entered, roundAnnounced, leftReady, rightReady, visible, play.phase, match.sequence, state.locked, state.storageBlocked]);
  // A restored active match may mount before its tab lock is acquired; focus only once selectable.
  useEffect(() => {
    if (play.phase === 'active' && !state.locked && !state.storageBlocked) firstCard.current?.focus({ preventScroll: true });
  }, [play.phase, state.locked, state.storageBlocked]);
  const duration = play.snapshot.rules.matchDurationMs;
  const remaining = play.phase === 'preparation' ? duration : play.phase === 'active' && play.deadline !== null ? Math.max(0, play.deadline - state.now) : 0;
  const lastEvent = play.phase === 'feedback' ? play.events.at(-1) : undefined;
  const roundName = match.roundSize === 2 ? '결승' : match.roundSize === 4 ? '4강 · 준결승' : `${match.roundSize}강`;
  const showRoundAnnouncement = play.phase === 'preparation' && !roundAnnounced;
  const urgent = play.phase === 'active' && remaining <= 3000;
  const roundSteps = [32, 16, 8, 4, 2].filter(size => size <= play.snapshot.size);
  const canChoose = play.phase === 'active' && !state.locked && !state.storageBlocked;
  const total = play.snapshot.size - 1;
  const note = play.phase === 'preparation' ? '두 카드를 준비하고 있어요.' : lastEvent?.reason === 'TIMEOUT_RANDOM' ? '시간초과 · 랜덤 진출' : lastEvent ? '선택 확정' : '더 끌리는 하나를 골라요.';
  return <section className={`play-page phase-${play.phase}${urgent ? ' is-urgent' : ''}${match.roundSize === 2 ? ' is-final' : ''}`}>
    <ol className="round-route" aria-label="월드컵 라운드 진행">
      {roundSteps.map(size => <li key={size} aria-current={size === match.roundSize ? 'step' : undefined} data-state={size > match.roundSize ? 'complete' : size === match.roundSize ? 'current' : 'upcoming'}>
        <span>{size === 2 ? '결승' : `${size}강`}</span>{size === 4 && <small>준결승</small>}
      </li>)}
    </ol>
    <div className="match-heading"><Heading eyebrow={`MATCH ${twoDigits(match.sequence + 1)} / ${twoDigits(total)}`} subtitle={`${roundName} ${match.roundMatch} / ${match.roundTotal}`}>{roundName === '결승' ? '마지막, 하나.' : '지금 더 끌리는 건?'}</Heading><span className="round-badge">{roundName}</span></div>
    <div className={`match-clock ${urgent ? 'is-urgent' : ''}`}>
      <span className="tiny-label">{urgent ? '마지막 3초 · 지금 골라요' : 'ONE MATCH. SEVEN SECONDS.'}</span><span className="clock-value" aria-hidden="true">{(remaining / 1000).toFixed(1)}<small>s</small></span>
      <span className="visually-hidden">각 경기의 제한시간은 7초입니다. 시간 초과 시 무작위로 선택됩니다.</span>
      <div className="clock-track" aria-hidden="true"><span style={{ transform: `scaleX(${remaining / duration})` }} /></div>
    </div>
    <div className="split-deck" aria-label={`${roundName} 후보 선택`}>
      {[match.left, match.right].map((candidate, index) => {
        const won = lastEvent?.winnerId === candidate.id;
        const lost = !!lastEvent && !won;
        return <button ref={index === 0 ? firstCard : undefined} key={candidate.id} data-testid="candidate-button" className={`battle-card ${index === 1 ? 'battle-card--bottom' : ''} ${won ? 'is-winner' : ''} ${lost ? 'is-loser' : ''}`} disabled={!canChoose} onClick={() => actions.choose(candidate.id)} aria-label={`${candidate.name} 선택`}>
          <span className="battle-surface">
          <CandidateArt candidate={candidate} number={index === 0 ? 'A' : 'B'} onSettled={index === 0 ? leftSettled : rightSettled} />
          <span className="battle-copy"><span className="battle-choice">{index === 0 ? 'A' : 'B'} / YOUR PICK</span><strong>{candidate.name}</strong><Tags candidate={candidate} />{won && <span className="winner-stamp">{roundName === '결승' ? '우승' : '진출'} ↗</span>}</span>
          <span className="card-arrow" aria-hidden="true">↗</span>
          </span>
        </button>;
      })}
      <span className="match-divider" aria-hidden="true"><span className="match-spark" /><span className="versus"><span>VS</span></span>
        {urgent && <span key={Math.ceil(remaining / 1000)} className="urgent-countdown">{Math.ceil(remaining / 1000)}</span>}
      </span>
      {showRoundAnnouncement && <div className="round-announcement" data-testid="round-announcement" role="status">
        <span className="tiny-label">{match.sequence === 0 ? 'LET’S PLAY' : match.roundSize === 2 ? 'THE FINAL' : 'NEXT ROUND'}</span>
        <strong>{roundName}{match.sequence === 0 ? ' 시작' : match.roundSize === 2 ? '' : ' 진출'}</strong>
        <span>{match.roundSize === 2 ? '마지막 두 후보. 이제 하나만.' : `후보 ${match.roundSize}개 · 이번 라운드 ${match.roundTotal}번의 선택`}</span>
      </div>}
    </div>
    <p className={`match-note ${lastEvent?.reason === 'TIMEOUT_RANDOM' ? 'match-note--timeout' : ''}`} role="status">{note}</p>
    <div className="match-progress" aria-label={`${play.events.length} / ${total} 경기 완료`}>
      {Array.from({ length: total }, (_, index) => <span key={index} className={index < play.events.length ? 'is-complete' : ''} aria-hidden="true" />)}
    </div>
    <p className="fine-print match-rule">시간이 지나면 랜덤 진출 · 선택 후 되돌리기 없음</p>
  </section>;
}

function ChampionScreen({ state, actions }: ViewProps) {
  const candidate = state.champion;
  if (!candidate) return null;
  const events = state.play?.events ?? [];
  const direct = events.filter((event) => event.reason === 'USER_SELECTED').length;
  const random = events.length - direct;
  return <section className="flow-page champion-page">
    <Heading eyebrow="THE LAST ONE STANDING" subtitle="많은 가능성 끝에, 오늘의 선택.">너의 <span className="accent-text">챔피언.</span></Heading>
    <div className="champion-card"><span className="champion-ribbon">CHAMPION / 01</span><CandidateArt candidate={candidate} number="01" /><span className="champion-stamp" aria-hidden="true">WINNER</span>
      <div className="champion-copy"><p className="tiny-label">YOUR FINAL PICK</p><h2>{candidate.name}</h2><Tags candidate={candidate} /></div><span className="champion-star" aria-hidden="true">✳</span>
    </div>
    <div className="result-stats"><div><strong>{events.length}</strong><span>번의 선택</span></div><div><strong>{direct}</strong><span>직접 선택</span></div><div><strong>{random}</strong><span>랜덤 진출</span></div></div>
    <p className="save-state" role="status">{state.saved ? '선택 기록과 우승 결과가 저장됐어요.' : state.saving ? '우승 결과를 저장하고 있어요. 잠시 기다려 주세요.' : '아직 결과가 저장되지 않았어요. 저장 후 공유할 수 있어요.'}</p>
    {random > 0 && <p className="fine-print">랜덤 선택도 경기 기록에는 남지만, 취향에는 반영하지 않아요.</p>}
    <div className="action-stack"><button className="button button--primary" disabled={!state.saved || state.busy || state.locked} onClick={actions.share}>{state.shareUrl ? '공유 링크 다시 확인' : '이 월드컵 공유하기'} <Arrow /></button>
      {state.shareUrl && <div className="share-link-panel"><label htmlFor="share-url">같은 후보, 같은 대진. 다른 선택은 어떨까?</label><input id="share-url" type="url" readOnly value={state.shareUrl} onFocus={(event) => event.currentTarget.select()} /><button className="button button--outline" disabled={state.busy} onClick={actions.copyShare}>링크 복사 <span aria-hidden="true">↗</span></button></div>}
      <button className="text-button" disabled={state.busy || state.saving || !state.saved || state.locked} onClick={actions.newCup}>{state.homeReturnsToExisting ? '내 월드컵으로 돌아가기' : '새 고민으로 시작하기'} <Arrow /></button>
      {state.homeReturnsToExisting && <p className="fine-print">진행 중인 월드컵이 있다면 이어서 열려요.</p>}</div>
  </section>;
}

function ShareScreen({ state, actions }: ViewProps) {
  const shared = state.shared;
  if (!shared) return null;
  const champion = shared.snapshot.candidates.find((candidate) => candidate.id === shared.championId);
  const frozenAt = new Date(shared.snapshot.frozenAt);
  return <section className="preview-page share-page">
    <Heading eyebrow="SAME CONTENDERS. YOUR CHOICE." subtitle="친구의 선택과 같을까, 다를까? 같은 후보와 대진으로 골라봐요.">이번엔,<br /><span className="accent-text">너의 차례.</span></Heading>
    <h2 className="shared-title">{shared.snapshot.title}</h2>
    {champion && <div className="original-champion"><span className="tiny-label">만든 사람의 CHAMPION</span><strong>{champion.name}</strong><Tags candidate={champion} /><span className="original-mark" aria-hidden="true">✳</span></div>}
    <div className="roster-heading"><h2>함께 고를 후보 <span>{shared.snapshot.size}강</span></h2><span className="tiny-label">SAME BRACKET</span></div>
    <CandidateList candidates={shared.snapshot.candidates} />
    <p className="fine-print frozen-note">{Number.isNaN(frozenAt.getTime()) ? '저장된' : frozenAt.toLocaleString('ko-KR')} 대진 그대로 진행해요.<br />후보를 새로 만들지 않으며, 내 결과는 별도로 저장돼요.<br />공유 플레이는 AI를 새로 호출하지 않아요.</p>
    <div className="action-stack"><button className="button button--primary" disabled={state.busy || state.locked || state.storageBlocked} onClick={actions.replay}>이 월드컵 해보기 <Arrow /></button><button className="text-button" disabled={state.busy || state.locked} onClick={actions.newCup}>내 월드컵으로 돌아가기 <Arrow /></button><p className="fine-print">진행 중인 월드컵이 있다면 이어서 열려요.</p></div>
  </section>;
}

export function AppView({ state, actions }: ViewProps) {
  const playing = state.screen === 'play';
  useEffect(() => { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); }, [state.screen]);
  const screens: Record<ViewState['screen'], ReactNode> = {
    input: <InputScreen state={state} actions={actions} />,
    size: <SizeScreen state={state} actions={actions} />,
    clarification: <ClarificationScreen state={state} actions={actions} />,
    generating: <GeneratingScreen state={state} actions={actions} />,
    preview: <PreviewScreen state={state} actions={actions} />,
    play: <PlayScreen state={state} actions={actions} />,
    champion: <ChampionScreen state={state} actions={actions} />,
    'share-loading': <section className="flow-page"><Heading eyebrow="OPENING A WORLD CUP">같은 대진을<br />불러오는 중.</Heading><div className="loading-line" aria-hidden="true"><span /></div><p role="status" className="lede">공유된 후보와 결과를 확인하고 있어요.</p></section>,
    share: <ShareScreen state={state} actions={actions} />,
    'share-error': <section className="flow-page"><Heading eyebrow="LINK UNAVAILABLE">대진을<br />열지 못했어요.</Heading><p className="lede">안내를 확인한 뒤 다시 시도하거나, 내 월드컵으로 돌아가세요.</p><button className="button button--outline" disabled={state.busy || state.locked} onClick={actions.newCup}>내 월드컵으로 돌아가기 <Arrow /></button><p className="fine-print">진행 중인 월드컵이 있다면 이어서 열려요.</p></section>,
  };
  return <div className={`app-shell ${playing ? 'is-playing' : ''}`}>
    <a className="skip-link" href="#main-content">본문으로 이동</a>
    {state.synthetic && <div className="development-banner" role="note">개발용 합성 후보 · 실제 AI 추천이 아니에요</div>}
    <header className="site-header"><div className="brand" aria-label="Dynamic AI World Cup"><span className="brand-mark" aria-hidden="true">W<span>↗</span></span><span>WORLD<br />CUP<span className="brand-period">.</span></span></div><span className="header-caption">A LITTLE LESS THINKING.<br /><b>A LITTLE MORE YOU.</b></span></header>
    <main id="main-content" data-testid={`screen-${state.screen}`} className={`main-content screen-${state.screen}`} tabIndex={-1}>
      <Problems state={state} actions={actions} />
      {screens[state.screen]}
    </main>
    {!playing && <footer className="site-footer"><span>YOUR CHOICE. YOUR WORLD.</span><span>매 경기 7초, 마지막엔 하나.</span></footer>}
  </div>;
}
