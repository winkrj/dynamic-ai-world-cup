import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// Every API/image request is intercepted. This suite never invokes a candidate provider.
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:8080');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname) || base.pathname !== '/') throw Error('Use a loopback app origin.');
const snapshot = JSON.parse(await readFile(new URL('../contracts/fixtures/snapshot-8.json', import.meta.url), 'utf8'));
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const directory = 'reports/local/browser';
await mkdir(directory, { recursive: true });
const failures = [];
async function intercept(context, handler) {
  await context.route('**/api/v1/**', async route => {
    const { status = 200, body, headers = {} } = await handler(route.request());
    await route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) });
  });
}
const apiError = code => ({ code, message: 'Synthetic test error', requestId: 'synthetic-ui-test', retryable: false });
async function createInput(page) {
  await page.goto(base.href);
  await page.getByRole('textbox').fill('합성 UI 예외 검증');
  await page.getByRole('button', { name: /월드컵 만들기/ }).click();
  await page.getByRole('button', { name: /후보.*만들기/ }).click();
}

try {
  const failed = await browser.newContext({ viewport: { width: 360, height: 800 } });
  let generationCount = 0;
  await intercept(failed, request => {
    if (request.method() === 'POST') { generationCount++; return { status: 202, body: { jobId: 'failed-job', status: 'QUEUED', draftId: null, error: null } }; }
    return { body: { jobId: 'failed-job', status: 'FAILED', draftId: null, error: apiError('QUALITY_GATE_FAILED') } };
  });
  const failurePage = await failed.newPage();
  failurePage.on('pageerror', error => failures.push(error.message));
  await createInput(failurePage);
  await failurePage.getByRole('button', { name: /고민 수정하기/ }).waitFor();
  await failurePage.reload();
  await failurePage.getByRole('button', { name: /고민 수정하기/ }).waitFor();
  assert.equal(await failurePage.locator('.generating-page').getAttribute('aria-busy'), 'false');
  await failurePage.getByRole('button', { name: /고민 수정하기/ }).click();
  await failurePage.getByTestId('screen-input').waitFor();
  assert.equal(generationCount, 1, 'Reload must not create a second job');
  await failed.close();

  for (const width of [360, 1280]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: width === 1280 ? 'reduce' : 'no-preference' });
    const changed = structuredClone(snapshot);
    changed.candidates.find(c => c.id === changed.initialOrder[0]).name = '집에서 혼자 천천히 오래 즐기는 아주 긴 이름의 취미 후보를 고르는 연습';
    changed.candidates.find(c => c.id === changed.initialOrder[0]).imageUrl = 'https://worldcup-image.invalid/slow';
    changed.candidates.find(c => c.id === changed.initialOrder[1]).imageUrl = 'https://worldcup-image.invalid/fail';
    await context.addInitScript(value => {
      if (!localStorage.getItem('worldcup:fixture-seeded')) {
        localStorage.setItem('worldcup:fixture-seeded', '1');
        localStorage.setItem('worldcup:flow:v1:/', JSON.stringify({ version: 1, savedAt: Date.now(), flow: {
          kind: 'play', acknowledged: 0, play: { formatVersion: 1, sessionId: 'fixture-session', snapshot: value,
            events: [], phase: 'preparation', startedAt: null, deadline: null },
        } }));
      }
    }, changed);
    await context.route('https://worldcup-image.invalid/**', async route => {
      if (route.request().url().endsWith('/slow')) await new Promise(resolve => setTimeout(resolve, 2500));
      await route.abort();
    });
    await intercept(context, request => {
      const events = request.postDataJSON()?.events ?? [];
      return { body: { nextSequence: events.at(-1)?.sequence + 1, status: 'PLAYING', championId: null } };
    });
    const page = await context.newPage();
    page.on('pageerror', error => failures.push(error.message));
    await page.goto(base.href, { waitUntil: 'domcontentloaded' });
    await page.getByTestId('screen-play').waitFor();
    await page.waitForTimeout(500);
    let play = await page.evaluate(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow.play);
    assert.equal(play.phase, 'preparation', 'Slow image must not consume the deadline');
    assert.equal(play.deadline, null);
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow.play.phase === 'active');
    play = await page.evaluate(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow.play);
    assert.equal(play.deadline - play.startedAt, 7000);
    assert.equal(await page.locator('.candidate-art--fallback').count(), 2);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    const first = page.getByTestId('candidate-button').first();
    const second = page.getByTestId('candidate-button').nth(1);
    const a = await first.boundingBox(); const b = await second.boundingBox();
    assert(a && b && b.y >= a.y + a.height, 'A must stay vertically stacked');
    assert(a.height >= 44 && b.height >= 44);
    assert.equal(await first.evaluate(element => element === document.activeElement), true, 'Keyboard focus reaches first candidate');
    await page.screenshot({ path: `${directory}/fallback-long-${width}.png`, fullPage: true });
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow.play.events.length === 1);
    assert.equal((await page.evaluate(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow.play.events[0])).reason, 'USER_SELECTED');
    await context.close();
  }
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ failedReloadRecovery: true, generationCallsOnReload: 0, slowImageFallbackMs: 2000, keyboardSelection: true, widths: [360, 1280], verticalA: true, browserErrors: 0, mockedApiOnly: true }));
} finally { await browser.close(); }
