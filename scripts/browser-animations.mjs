import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// Synthetic API only. Never use the public deployment or a paid generation provider here.
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:15173');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname)
  || base.username || base.password || base.pathname !== '/' || base.search || base.hash) throw Error('Use a loopback app origin.');
const snapshot = JSON.parse(await readFile(new URL('../contracts/fixtures/snapshot-8.json', import.meta.url), 'utf8'));
snapshot.candidates.forEach(candidate => { candidate.imageUrl = null; });
snapshot.candidates.find(candidate => candidate.id === snapshot.initialOrder[0]).name = '집에서 혼자 천천히 오래 즐기는 아주 긴 이름의 취미 후보를 고르는 연습';
const directory = 'reports/local/browser';
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const errors = [];
const results = [];
const epoch = new Date('2026-09-20T00:00:00Z');
const flow = page => page.evaluate(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow);
const motion = locator => locator.evaluate(element => ({ name: getComputedStyle(element).animationName,
  duration: getComputedStyle(element).animationDuration, opacity: getComputedStyle(element).opacity }));
const assertFits = async page => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);

try {
  for (const width of [360, 1280]) {
    for (const reducedMotion of ['no-preference', 'reduce']) {
      const reduce = reducedMotion === 'reduce';
      const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion });
      const requests = [];
      await context.route('**/api/v1/**', async route => {
        const request = route.request();
        requests.push(new URL(request.url()).pathname);
        assert.match(new URL(request.url()).pathname, /^\/api\/v1\/sessions\/animation-session\/selections$/);
        const last = request.postDataJSON().events.at(-1);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nextSequence: last.sequence + 1,
          status: last.sequence === 6 ? 'COMPLETED' : 'PLAYING', championId: last.sequence === 6 ? last.winnerId : null }) });
      });
      await context.addInitScript(value => {
        // Init scripts may run before Playwright installs its virtual Date.
        localStorage.setItem('worldcup:flow:v1:/', JSON.stringify({ version: 1, savedAt: value.savedAt, flow: {
          kind: 'play', acknowledged: 0, play: { formatVersion: 1, sessionId: 'animation-session', snapshot: value.snapshot,
            events: [], phase: 'preparation', startedAt: null, deadline: null },
        } }));
      }, { snapshot, savedAt: epoch.getTime() });
      const page = await context.newPage();
      page.on('pageerror', error => errors.push(error.message));
      await page.clock.install({ time: epoch });
      await page.clock.pauseAt(new Date(epoch.getTime() + 1000));
      await page.goto(base.href);
      await page.getByTestId('round-announcement').waitFor();
      await page.getByText('진행을 안전하게 열 수 없어요.').waitFor({ state: 'hidden' });
      await page.clock.runFor(550);
      await page.waitForFunction(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow.play.phase === 'active');
      const first = page.getByTestId('candidate-button').first();
      const second = page.getByTestId('candidate-button').nth(1);
      let current = await flow(page);
      assert.equal(current.play.deadline - current.play.startedAt, 7000);
      assert.equal(await first.evaluate(element => element === document.activeElement), true);
      assert.equal((await motion(first)).name, 'none', 'Active click targets must stay still.');
      assert.equal((await motion(first.locator('.battle-surface'))).name, reduce ? 'none' : 'card-pressure-top');
      assert.equal((await motion(second.locator('.battle-surface'))).name, reduce ? 'none' : 'card-pressure-bottom');
      assert.equal((await motion(page.locator('.versus > span'))).name, reduce ? 'none' : 'versus-hit');
      assert.equal((await motion(page.locator('.match-spark'))).name, reduce ? 'none' : 'spark-hit');
      if (!reduce) assert.equal((await motion(page.locator('.versus > span'))).duration, '0.9s');
      const a = await first.boundingBox(); const b = await second.boundingBox();
      assert(a && b && b.y >= a.y + a.height && a.height >= 44 && b.height >= 44);
      const versus = await page.locator('.versus').boundingBox();
      assert(versus && Math.abs(versus.y + versus.height / 2 - (a.y + a.height + b.y) / 2) < 1,
        'VS belongs in the actual gap, not halfway down unequal cards.');
      assert.equal(await page.locator('.match-divider').evaluate(element => {
        const focused = document.querySelector('.battle-card:focus-visible');
        return getComputedStyle(element).pointerEvents === 'none'
          && Number(getComputedStyle(element).zIndex) > Number(focused ? getComputedStyle(focused).zIndex : 0);
      }), true, 'The decorative divider must stay visible above focus without intercepting taps.');
      await assertFits(page);
      await page.screenshot({ path: `${directory}/animation-active-${width}-${reducedMotion}.png`, fullPage: true });
      await page.keyboard.press('Enter');
      await page.locator('.winner-stamp').waitFor();
      current = await flow(page);
      assert.equal(current.play.events.length, 1);
      assert.equal(current.feedbackUntil - await page.evaluate(() => Date.now()), 280);
      assert.equal((await motion(page.locator('.is-winner'))).name, reduce ? 'none' : 'winner-slam');
      assert.equal((await motion(page.locator('.is-loser'))).name, reduce ? 'none' : 'card-exit-bottom');
      assert.equal((await motion(page.locator('.winner-stamp'))).name, reduce ? 'none' : 'advance-stamp');
      if (!reduce) {
        assert.equal((await motion(page.locator('.is-winner'))).duration, '0.25s');
        assert.equal((await motion(page.locator('.winner-stamp'))).duration, '0.24s');
      }
      assert.equal(await first.isDisabled(), true);
      assert.equal(await second.isDisabled(), true);
      // Freeze decorative CSS halfway solely for a reviewable screenshot; gameplay uses its original clock.
      await page.evaluate(() => document.getAnimations().forEach(animation => { animation.pause(); animation.currentTime = 120; }));
      await page.screenshot({ path: `${directory}/animation-choice-${width}-${reducedMotion}.png`, fullPage: true });

      async function nextMatch() {
        await page.clock.runFor(300);
        // A round's first pair also waits for its 500 ms announcement gate.
        await page.clock.runFor(550);
        const next = await flow(page);
        assert.equal(next.play.phase, 'active');
        assert.equal(next.play.deadline - next.play.startedAt, 7000);
      }
      await nextMatch();
      await second.click();
      assert.equal((await motion(page.locator('.is-loser'))).name, reduce ? 'none' : 'card-exit-top');
      await nextMatch();
      const third = await flow(page);
      await page.clock.runFor(third.play.deadline - await page.evaluate(() => Date.now()) - 3050);
      assert.equal(await page.locator('.play-page.is-urgent').count(), 0);
      await page.clock.runFor(100);
      assert.equal(await page.locator('.play-page.is-urgent').count(), 1);
      assert.equal(await page.locator('.urgent-countdown').textContent(), '3');
      assert.equal((await motion(page.locator('.urgent-countdown'))).name, reduce ? 'none' : 'countdown-hit');
      if (!reduce) assert.equal((await motion(page.locator('.urgent-countdown'))).duration, '1s');
      await page.clock.runFor(1000);
      assert.equal(await page.locator('.urgent-countdown').textContent(), '2');
      await page.clock.runFor(1000);
      assert.equal(await page.locator('.urgent-countdown').count(), 1);
      assert.equal(await page.locator('.urgent-countdown').textContent(), '1');
      await page.screenshot({ path: `${directory}/animation-urgent-${width}-${reducedMotion}.png`, fullPage: true });
      await page.clock.runFor(1000);
      assert.equal((await flow(page)).play.events.at(-1).reason, 'TIMEOUT_RANDOM');
      assert.match(await page.locator('.match-note').textContent(), /시간초과 · 랜덤 진출/);
      for (let sequence = 3; sequence < 7; sequence++) {
        await nextMatch();
        await first.click();
        assert.equal((await flow(page)).play.events.length, sequence + 1);
      }
      assert.equal(await page.locator('.winner-stamp').textContent(), '우승 ↗');
      await page.clock.runFor(300);
      await page.getByTestId('screen-champion').waitFor();
      assert.equal((await motion(page.locator('.champion-stamp'))).name, reduce ? 'none' : 'champion-stamp');
      assert.equal((await motion(page.locator('.champion-copy'))).name, reduce ? 'none' : 'champion-reveal');
      assert.equal(await page.locator('.champion-stamp').textContent(), 'WINNER');
      await page.waitForFunction(() => document.querySelector('.save-state')?.textContent?.includes('저장됐어요'));
      if (reduce) assert.equal((await motion(page.locator('.champion-copy'))).opacity, '1');
      await page.waitForTimeout(550);
      await assertFits(page);
      await page.screenshot({ path: `${directory}/animation-champion-${width}-${reducedMotion}.png`, fullPage: true });
      assert.equal((await flow(page)).play.events.length, 7);
      assert(requests.length > 0);
      results.push({ width, reducedMotion, sevenSecondDeadline: true, feedbackMs: 280, games: 7 });
      await context.close();
    }
  }

  for (const reducedMotion of ['no-preference', 'reduce']) {
    const context = await browser.newContext({ viewport: { width: 360, height: 900 }, reducedMotion });
    await context.route('**/api/v1/**', route => route.fulfill({ status: route.request().method() === 'POST' ? 202 : 200,
      contentType: 'application/json', body: JSON.stringify({ jobId: 'animation-loading', status: 'RUNNING', draftId: null, error: null }) }));
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base.href);
    await page.getByRole('textbox').fill('합성 애니메이션 검사');
    await page.getByRole('button', { name: /월드컵 만들기/ }).click();
    await page.getByRole('button', { name: /후보.*만들기/ }).click();
    await page.locator('.loading-status').waitFor();
    assert.equal(await page.locator('.loading-card').first().evaluate(element => getComputedStyle(element, '::after').animationName),
      reducedMotion === 'reduce' ? 'none' : 'card-shimmer');
    assert.equal(await page.locator('.loading-status').evaluate(element => getComputedStyle(element, '::before').animationName),
      reducedMotion === 'reduce' ? 'none' : 'status-beat');
    assert.equal(await page.getByRole('progressbar').count(), 0, 'Indeterminate loading must not invent progress.');
    await assertFits(page);
    await page.screenshot({ path: `${directory}/animation-loading-360-${reducedMotion}.png`, fullPage: true });
    await context.close();
  }
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ results, loadingShimmer: true, stationaryClickTargets: true, keyboardSelection: true,
    loserBothDirections: true, threeSecondUrgency: true, timeoutAndFinalUnchanged: true,
    reducedMotionLegible: true, browserErrors: 0, mockedApiOnly: true }));
} finally { await browser.close(); }
