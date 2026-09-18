import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// All API routes are intercepted. This test must never create a real generation job.
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:15173');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname)
    || base.username || base.password || base.pathname !== '/' || base.search || base.hash) throw Error('Use a loopback app origin.');
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const directory = 'reports/local/browser';
await mkdir(directory, { recursive: true });
const failures = [];
const original = '혼자 할 거야. 운동은 싫고 월 예산은 10만 원이야.\n집에서 하루 30분만 쓸 수 있어.';
const answer = '새로 시작할 취미를 고르고 싶어요.';
const question = '무엇을 고르는 월드컵인가요? 비교할 대상이나 활동을 알려주세요.';

try {
  for (const width of [360, 1280]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    const posts = [];
    await context.route('**/api/v1/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path === '/api/v1/generation-jobs' && request.method() === 'POST') {
        posts.push({ body: request.postDataJSON(), key: request.headers()['idempotency-key'] });
        if (posts.length === 2) return route.fulfill({ status: 429, contentType: 'application/json', headers: { 'Retry-After': '60' },
          body: JSON.stringify({ code: 'RATE_LIMITED', message: 'Synthetic quota rejection', requestId: 'clarification-quota', retryable: true }) });
        return route.fulfill({ status: 202, contentType: 'application/json',
          body: JSON.stringify({ jobId: `question-${posts.length}`, status: 'QUEUED', draftId: null, error: null }) });
      }
      assert.equal(request.method(), 'GET');
      assert.match(path, /^\/api\/v1\/generation-jobs\/question-[13]$/);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ jobId: path.split('/').at(-1),
        status: 'FAILED', draftId: null, error: { code: 'CLARIFICATION_REQUIRED', message: 'Private model explanation',
          requestId: 'clarification-test', retryable: false } }) });
    });
    const page = await context.newPage();
    page.on('pageerror', error => failures.push(error.message));
    await page.clock.install({ time: new Date('2026-09-18T05:00:00Z') });
    await page.goto(base.href);
    await page.getByRole('textbox').fill(original);
    await page.getByRole('button', { name: /월드컵 만들기/ }).click();
    await page.locator('input[value="32"]').check();
    await page.getByRole('button', { name: /후보 32개 만들기/ }).click();
    await page.getByTestId('screen-clarification').waitFor();
    assert.equal(await page.locator('.clarification-original').textContent(), original);
    assert.match(await page.locator('#clarification-cost').textContent(), /접수되면 하루 횟수를 1회 더/);
    assert.equal(posts.length, 1);
    const textbox = page.getByRole('textbox', { name: question });
    await textbox.fill('😀'.repeat(500));
    const submit = page.getByRole('button', { name: /답변을 더해 후보 32개 만들기/ });
    assert.equal(await submit.isDisabled(), true);
    assert.equal(await textbox.getAttribute('aria-invalid'), 'true');
    assert.equal(await textbox.inputValue(), '😀'.repeat(500));
    await textbox.fill(answer);
    await page.reload();
    await page.getByTestId('screen-clarification').waitFor();
    assert.equal(await textbox.inputValue(), answer);
    assert.equal(posts.length, 1);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: `${directory}/clarification-${width}.png`, fullPage: true });
    await submit.focus(); await page.keyboard.press('Enter');
    await page.locator('.retry-countdown').waitFor();
    assert.equal(posts.length, 2);
    assert.notEqual(posts[0].key, posts[1].key);
    assert.deepEqual(posts[1].body, { ...posts[0].body, prompt: `${original}\n\n추가 답변: ${answer}` });
    await page.reload();
    await page.locator('.retry-countdown').waitFor();
    assert.equal(posts.length, 2, 'Reload must not POST an answered request that was rejected or ambiguous.');
    const retry = page.getByRole('button', { name: '다시 시도', exact: true });
    assert.equal(await retry.isDisabled(), true);
    await page.clock.fastForward(60_000);
    await page.waitForFunction(() => !document.querySelector('.message-actions .button')?.disabled);
    assert.equal(posts.length, 2, 'Retry deadline must not automatically POST.');
    await retry.click();
    await page.getByRole('button', { name: '고민 수정하기', exact: false }).waitFor();
    assert.equal(posts.length, 3);
    assert.deepEqual(posts[2], posts[1]);
    assert.equal(await page.getByTestId('screen-clarification').count(), 0);
    await page.getByRole('button', { name: '고민 수정하기', exact: false }).click();
    assert.equal(await page.getByRole('textbox').inputValue(), posts[1].body.prompt);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await context.close();
  }
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ widths: [360, 1280], questionOnce: true, originalAndSizePreserved: true,
    answerRestored: true, explicitNewKey: true, unicodeLengthWithoutTruncation: true, retryAfterReload: true,
    autoPosts: 0, keyboardSubmission: true, reducedMotion: true, browserErrors: 0, mockedApiOnly: true }));
} finally { await browser.close(); }
