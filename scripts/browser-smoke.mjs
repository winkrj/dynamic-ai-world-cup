import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { chromium } from 'playwright';

// Only run against a separately started synthetic dev server with its own disposable database.
// This test creates four generation jobs and play/share records; it never deletes existing records.
if (process.env.WORLDCUP_SYNTHETIC_E2E !== '1') throw Error('Explicit WORLDCUP_SYNTHETIC_E2E=1 is required. Never point this test at a live AI server.');
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:8080');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname)
  || base.username || base.password || base.pathname !== '/' || base.search || base.hash) throw Error('Use a loopback HTTP origin.');
const directory = resolve('reports/local/browser');
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const errors = [];
const results = [];
let generationPosts = 0;
const trackGeneration = request => {
  if (request.method() === 'POST' && /\/api\/v1\/(generation-jobs$|drafts\/[^/]+\/regenerations$)/.test(request.url())) generationPosts++;
};

async function flow(page) {
  return page.evaluate(() => {
    const raw = localStorage.getItem(`worldcup:flow:v1:${location.pathname}`);
    return raw ? JSON.parse(raw).flow : null;
  });
}
async function waitFlow(page, kind, phase, count) {
  await page.waitForFunction(({ kind, phase, count }) => {
    const raw = localStorage.getItem(`worldcup:flow:v1:${location.pathname}`);
    if (!raw) return false;
    const f = JSON.parse(raw).flow;
    return f.kind === kind && (!phase || f.play?.phase === phase) && (count === undefined || f.play?.events.length === count);
  }, { kind, phase, count }, { timeout: 20000 });
}
async function noOverflow(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'Horizontal overflow');
}
async function finish(page, size, initial = 0) {
  for (let sequence = initial; sequence < size - 1; sequence++) {
    await waitFlow(page, 'play', 'active', sequence);
    await page.getByTestId('candidate-button').first().click();
    await page.waitForFunction(sequence => {
      const f = JSON.parse(localStorage.getItem(`worldcup:flow:v1:${location.pathname}`)).flow;
      return f.play?.events.length === sequence + 1;
    }, sequence);
  }
  await waitFlow(page, 'play', 'completed', size - 1);
  await page.waitForFunction(() => JSON.parse(localStorage.getItem(`worldcup:flow:v1:${location.pathname}`)).flow.ack?.status === 'COMPLETED');
  const f = await flow(page);
  assert.equal(f.ack.nextSequence, size - 1);
  assert.equal(f.ack.championId, f.play.events.at(-1).winnerId);
  return f;
}

try {
  for (const size of [8, 16, 32]) {
    const context = await browser.newContext({ viewport: { width: 360, height: 800 }, reducedMotion: size === 32 ? 'reduce' : 'no-preference' });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    context.on('request', trackGeneration);
    await page.goto(base.href);
    await page.getByRole('textbox').first().fill('브라우저 연결 확인용 합성 취미 후보');
    await page.getByRole('button', { name: /다음|월드컵 만들기|강수 고르기/ }).first().click();
    await waitFlow(page, 'size');
    await page.locator(`input[type="radio"][value="${size}"]`).check();
    await page.getByRole('button', { name: /후보.*만들기/ }).click();
    await waitFlow(page, 'preview');
    assert.equal((await flow(page)).preview.candidates.length, size);
    assert((await flow(page)).preview.candidates.every(candidate => candidate.name.startsWith('[개발용]')), 'Requires synthetic dev profile');
    assert.equal(await page.getByTestId('candidate-preview').count(), size);
    await noOverflow(page);
    await page.screenshot({ path: `${directory}/preview-${size}-360.png`, fullPage: true });
    if (size === 8) {
      await page.getByRole('button', { name: /전체.*다시/ }).click();
      await page.waitForFunction(() => {
        const f = JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow;
        return f.kind === 'preview' && f.preview.version === 2;
      });
      assert.equal((await flow(page)).preview.regenerationsRemaining, 0);
    }
    await page.getByRole('button', { name: /이대로 시작/ }).click();
    await waitFlow(page, 'play', 'active', 0);
    const snapshot = (await flow(page)).play.snapshot;
    await noOverflow(page);
    await page.screenshot({ path: `${directory}/play-${size}-360.png`, fullPage: true });
    let initial = 0;
    if (size === 8) {
      const deadline = (await flow(page)).play.deadline;
      await page.reload();
      await waitFlow(page, 'play', 'active', 0);
      assert.equal((await flow(page)).play.deadline, deadline, 'Refresh must preserve deadline');
      // Browser visibility events are simulated; this is not an OS background-throttling test.
      await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' }); document.dispatchEvent(new Event('visibilitychange')); });
      await page.waitForTimeout(Math.max(0, deadline - Date.now()) + 200);
      assert.equal((await flow(page)).play.events.length, 0);
      await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' }); document.dispatchEvent(new Event('visibilitychange')); });
      await waitFlow(page, 'play', 'active', 1);
      assert.equal((await flow(page)).play.events[0].reason, 'TIMEOUT_RANDOM');
      initial = 1;
      const duplicate = await context.newPage();
      await duplicate.goto(base.href);
      await duplicate.getByText(/다른 탭/).first().waitFor();
      assert.equal(await duplicate.getByTestId('candidate-button').first().isDisabled(), true);
      await duplicate.close();
    }
    const completed = await finish(page, size, initial);
    await page.screenshot({ path: `${directory}/champion-${size}-360.png`, fullPage: true });
    await page.getByRole('button', { name: /공유.*만들기|공유하기|공유 주소 받기/ }).click();
    await page.waitForFunction(() => !!JSON.parse(localStorage.getItem(`worldcup:flow:v1:${location.pathname}`)).flow.share);
    const sharedUrl = (await flow(page)).share.url;
    if (size === 8) {
      const beforeReplay = generationPosts;
      const guest = await browser.newContext({ viewport: { width: 360, height: 800 }, reducedMotion: 'reduce' });
      guest.on('request', trackGeneration);
      const replay = await guest.newPage();
      replay.on('pageerror', error => errors.push(error.message));
      await replay.goto(sharedUrl);
      await replay.getByTestId('screen-share').waitFor();
      await noOverflow(replay);
      assert.deepEqual((await flow(replay)).shared.snapshot, snapshot);
      await replay.screenshot({ path: `${directory}/share-360.png`, fullPage: true });
      await replay.getByRole('button', { name: /이 월드컵 해보기|같은.*해보기|같은.*시작/ }).click();
      const replayed = await finish(replay, size);
      assert.notEqual(replayed.play.sessionId, completed.play.sessionId);
      assert.deepEqual(replayed.play.snapshot, snapshot);
      assert.equal(generationPosts, beforeReplay);
      await guest.close();
    }
    results.push({ size, decisions: completed.play.events.length, serverCompleted: true });
    await context.close();
  }
  const desktop = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await desktop.newPage();
  await page.goto(base.href);
  await noOverflow(page);
  await page.screenshot({ path: `${directory}/input-1280.png`, fullPage: true });
  await page.goto(new URL('/shares/does-not-exist', base).href);
  await page.getByTestId('screen-share-error').waitFor();
  assert.match(await page.textContent('body'), /찾을 수 없/);
  await desktop.close();
  assert.deepEqual(errors, [], 'Browser exceptions');
  assert.equal(generationPosts, 4, 'Unexpected duplicate generation calls');
  console.log(JSON.stringify({ results, generationPosts, sharedReplayWithoutGeneration: true, browserErrors: errors.length, screenshots: directory }));
} finally { await browser.close(); }
