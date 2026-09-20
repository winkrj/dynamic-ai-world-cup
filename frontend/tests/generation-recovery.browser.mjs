import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// Loopback UI only. Every API request is intercepted; no provider call or production data is used.
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:15173');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname)
    || base.username || base.password || base.pathname !== '/' || base.search || base.hash) throw Error('Use a loopback app origin.');
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const failures = [];
const directory = 'reports/local/browser';
await mkdir(directory, { recursive: true });
const prompt = '합성 검증용: 함께할 활동을 고르고 싶어.';
const epoch = Date.parse('2026-09-20T01:00:00Z');
const input = { prompt, size: 16, locale: 'ko-KR', timezone: 'Asia/Seoul' };
const editButton = page => page.getByRole('button', { name: /고민 수정하기/ });
const retryButton = page => page.getByRole('button', { name: '다시 시도', exact: true });
async function open(context) {
  const page = await context.newPage();
  page.on('pageerror', error => failures.push(error.message));
  await page.clock.install({ time: new Date(epoch) });
  await page.goto(base.href);
  return page;
}
async function generate(page) {
  await page.getByRole('textbox').fill(prompt);
  await page.getByRole('button', { name: /월드컵 만들기/ }).click();
  await page.getByRole('button', { name: /후보 16개 만들기/ }).click();
  await page.getByRole('region', { name: '문제 안내' }).waitFor();
}
async function fits(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
}

try {
  for (const width of [360, 1280]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    const posts = [];
    await context.route('**/api/v1/**', async route => {
      const request = route.request();
      if (request.method() === 'POST') {
        assert.equal(new URL(request.url()).pathname, '/api/v1/generation-jobs');
        posts.push({ key: request.headers()['idempotency-key'], body: request.postDataJSON() });
        if (posts.length === 1) return route.fulfill({ status: 429, contentType: 'text/html', headers: { 'Retry-After': '60' }, body: 'Synthetic proxy rejection' });
        return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ jobId: 'grounding-job', status: 'QUEUED', draftId: null, error: null }) });
      }
      assert.equal(new URL(request.url()).pathname, '/api/v1/generation-jobs/grounding-job');
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ jobId: 'grounding-job', status: 'FAILED', draftId: null,
        error: { code: 'GROUNDING_REQUIRED', message: 'Private model detail', requestId: 'grounding-ui', retryable: false } }) });
    });
    const page = await open(context);
    await generate(page);
    assert.equal(await retryButton(page).isDisabled(), true);
    assert.equal(await editButton(page).isEnabled(), true);
    assert.equal(await page.locator('.loading-deck').count(), 0);
    await page.reload();
    await page.locator('.retry-countdown').waitFor();
    assert.equal(posts.length, 1, 'A known rejected initial request never auto-POSTs on reload.');
    assert.equal(await editButton(page).isEnabled(), true);
    await fits(page);
    await page.screenshot({ path: `${directory}/generation-recovery-wait-${width}.png`, fullPage: true });
    await page.clock.fastForward(60_000);
    await page.waitForFunction(() => !document.querySelector('.message-actions .button')?.disabled);
    assert.equal(posts.length, 1, 'Expiry alone never POSTs.');
    await retryButton(page).focus(); await page.keyboard.press('Enter');
    await page.getByText('최신 장소 정보는 확인할 수 없어요', { exact: true }).waitFor();
    assert.deepEqual(posts, [posts[0], posts[0]], 'Explicit retry keeps its exact body/key.');
    assert.equal(await page.getByTestId('screen-clarification').count(), 0);
    assert.equal(await retryButton(page).count(), 0);
    assert.doesNotMatch(await page.locator('body').textContent(), /Private model detail/);
    await page.reload();
    await page.getByText('최신 장소 정보는 확인할 수 없어요', { exact: true }).waitFor();
    assert.equal(posts.length, 2, 'A failed job stays terminal after reload.');
    await fits(page);
    await page.screenshot({ path: `${directory}/generation-recovery-grounding-${width}.png`, fullPage: true });
    await editButton(page).focus(); await page.keyboard.press('Enter');
    assert.equal(await page.getByRole('textbox').inputValue(), prompt);
    assert.equal(posts.length, 2, 'Editing does not submit anything.');
    await context.close();

    const legacyContext = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    let legacyRequests = 0;
    await legacyContext.route('**/api/v1/**', async route => { legacyRequests++; await route.abort(); });
    const answered = `${prompt}\n\n추가 답변: 합성 조건은 그대로 유지해 줘.`;
    await legacyContext.addInitScript(({ input, answered, epoch }) => {
      localStorage.setItem('worldcup:flow:v1:/', JSON.stringify({ version: 1, savedAt: epoch, flow: {
        kind: 'generation', input: { ...input, prompt: answered, size: 32 }, key: 'legacy-answer-key',
        clarificationUsed: true, manualRetry: true, retryAt: epoch + 3_600_000,
      } }));
    }, { input, answered, epoch });
    const legacy = await open(legacyContext);
    await legacy.locator('.retry-countdown').waitFor();
    assert.equal(await retryButton(legacy).isDisabled(), true);
    assert.equal(await editButton(legacy).isEnabled(), true);
    await editButton(legacy).click();
    assert.equal(await legacy.getByRole('textbox').inputValue(), answered);
    await legacy.getByRole('button', { name: /월드컵 만들기/ }).click();
    assert.equal(await legacy.locator('input[value="32"]').isChecked(), true);
    assert.equal(legacyRequests, 0);
    await fits(legacy);
    await legacyContext.close();

    const missingContext = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    let missingPosts = 0;
    await missingContext.route('**/api/v1/**', async route => {
      missingPosts++;
      return route.fulfill({ status: 429, contentType: 'application/json', body: JSON.stringify({ code: 'RATE_LIMITED', message: 'Synthetic rejection', requestId: 'no-deadline', retryable: true }) });
    });
    const missing = await open(missingContext);
    await generate(missing); await missing.reload();
    await missing.getByRole('region', { name: '문제 안내' }).waitFor();
    assert.equal(missingPosts, 1);
    assert.equal(await missing.locator('.retry-countdown').count(), 0, 'No invented Retry-After countdown.');
    assert.equal(await retryButton(missing).isEnabled(), true);
    await editButton(missing).click();
    assert.equal(await missing.getByRole('textbox').inputValue(), prompt);
    assert.equal(missingPosts, 1);
    await missingContext.close();

    const ambiguousContext = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    const ambiguousPosts = [];
    await ambiguousContext.route('**/api/v1/**', async route => {
      const request = route.request();
      ambiguousPosts.push({ key: request.headers()['idempotency-key'], body: request.postDataJSON() });
      if (ambiguousPosts.length > 1) return route.abort('failed');
      return route.fulfill({ status: 429, contentType: 'application/json', body: JSON.stringify({ code: 'RATE_LIMITED', message: 'Synthetic rejection', requestId: 'ambiguous-retry', retryable: true }) });
    });
    const ambiguous = await open(ambiguousContext);
    await generate(ambiguous); await retryButton(ambiguous).click();
    await ambiguous.getByText('연결을 확인해 주세요', { exact: true }).waitFor();
    assert.equal(await editButton(ambiguous).count(), 0, 'A retry with an unknown outcome cannot be abandoned.');
    await ambiguous.reload();
    await ambiguous.getByRole('region', { name: '문제 안내' }).waitFor();
    assert.equal(await editButton(ambiguous).count(), 0);
    assert.equal(ambiguousPosts.length, 2, 'Unknown retry remains manual after reload.');
    await retryButton(ambiguous).click();
    await ambiguous.getByText('연결을 확인해 주세요', { exact: true }).waitFor();
    assert.deepEqual(ambiguousPosts, [ambiguousPosts[0], ambiguousPosts[0], ambiguousPosts[0]]);
    await ambiguousContext.close();
  }
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ widths: [360, 1280], cases: 8, initial429Reload: true, legacy429Recovery: true,
    missingRetryAfter: true, expiryAutoPosts: 0, editPosts: 0, exactRetry: true, ambiguousCannotEdit: true,
    groundingTerminalAcrossReload: true, keyboard: true, reducedMotion: true, browserErrors: 0, mockedApiOnly: true }));
} finally { await browser.close(); }
