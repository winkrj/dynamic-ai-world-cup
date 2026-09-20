import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// Run against a loopback frontend. Every API request is synthetic; no provider is called.
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:8080');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname) || base.pathname !== '/') throw Error('Use a loopback app origin.');
const preview = JSON.parse(await readFile(new URL('../../contracts/fixtures/preview-8.json', import.meta.url), 'utf8'));
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const directory = 'reports/local/browser';
await mkdir(directory, { recursive: true });
const failures = [];
const policyText = '짧은 시간에 여러 번 만들면 잠시 기다려야 할 수 있어요.';
async function openSize(page) {
  page.on('pageerror', error => failures.push(error.message));
  await page.goto(base.href);
  assert.match(await page.locator('.input-reassurance').textContent(), /반복 요청은 일시적으로 제한될 수 있어요/);
  assert.doesNotMatch(await page.locator('.input-reassurance').textContent(), /하루 2회|자정/);
  await page.getByRole('textbox').fill('합성 생성 정책 검증');
  await page.getByRole('button', { name: /월드컵 만들기/ }).click();
  assert.equal(await page.locator('input[value="16"]').isChecked(), true, '16 remains the default.');
  assert.equal(await page.locator('.size-quality-note').count(), 0);
  await page.locator('input[value="32"]').check();
  assert.match(await page.locator('.size-quality-note').textContent(), /비슷한 후보가 섞일 수 있어요/);
  await page.locator('input[value="8"]').check();
  assert.equal(await page.locator('.size-quality-note').count(), 0, 'The 32-candidate guidance is scoped to that choice.');
  assert.match(await page.locator('.generation-policy').textContent(), /같은 요청 재전송·공유 플레이는 AI를 새로 호출하지 않아요/);
  assert.doesNotMatch(await page.locator('.generation-policy').textContent(), /하루 2회|자정/);
}
async function assertFits(page) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
}

try {
  for (const width of [360, 1280]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    await context.route('**/api/v1/**', async route => {
      const path = new URL(route.request().url()).pathname;
      const isDraft = path === `/api/v1/drafts/${preview.draftId}`;
      const body = isDraft ? preview : { jobId: 'policy-job', status: 'READY', draftId: preview.draftId, error: null };
      await route.fulfill({ status: route.request().method() === 'POST' ? 202 : 200, contentType: 'application/json', body: JSON.stringify(body) });
    });
    const page = await context.newPage();
    await openSize(page);
    assert.match(await page.locator('.generation-policy').textContent(), new RegExp(policyText));
    await assertFits(page);
    await page.screenshot({ path: `${directory}/generation-policy-size-${width}.png`, fullPage: true });
    const generate = page.getByRole('button', { name: /후보 8개 만들기/ });
    await generate.focus();
    await page.keyboard.press('Enter');
    await page.getByTestId('screen-preview').waitFor();
    assert.match(await page.locator('.preview-quality-note').textContent(), /내 조건에 맞는지 확인해 주세요/);
    assert.match(await page.locator('.generation-policy').textContent(), new RegExp(policyText));
    const regenerate = page.getByRole('button', { name: /전체 다시 만들기 교체 남은 1회/ });
    assert.equal(await regenerate.count(), 1, 'Draft replacement allowance is not presented as remaining daily quota');
    const box = await regenerate.boundingBox();
    assert.ok(box && box.height >= 44);
    await assertFits(page);
    await page.screenshot({ path: `${directory}/generation-policy-preview-${width}.png`, fullPage: true });
    await context.close();
  }

  const context = await browser.newContext({ viewport: { width: 360, height: 900 }, reducedMotion: 'reduce' });
  const requests = [];
  await context.route('**/api/v1/**', async route => {
    const request = route.request();
    requests.push({ method: request.method(), body: request.postData(), key: request.headers()['idempotency-key'] });
    await route.fulfill({ status: 429, contentType: 'application/json', headers: { 'Retry-After': '86400' },
      body: JSON.stringify({ code: 'RATE_LIMITED', message: 'Synthetic daily limit', retryable: true, requestId: 'daily-policy-test' }) });
  });
  const page = await context.newPage();
  await page.clock.install({ time: new Date('2026-09-18T15:00:00Z') });
  await openSize(page);
  await page.getByRole('button', { name: /후보 8개 만들기/ }).click();
  await page.locator('.retry-countdown').waitFor();
  assert.match(await page.locator('.retry-countdown').textContent(), /시간/);
  const retry = page.getByRole('button', { name: '다시 시도', exact: true });
  assert.equal(await retry.isDisabled(), true);
  await assertFits(page);
  await page.screenshot({ path: `${directory}/generation-policy-daily-wait-360.png`, fullPage: true });
  await page.clock.fastForward(86_400_000);
  await page.waitForFunction(() => !document.querySelector('.message-actions .button')?.disabled);
  assert.equal(requests.length, 1, 'Reaching the daily reset must not automatically POST');
  assert.equal(await page.locator('.retry-countdown').count(), 0);
  await retry.click();
  assert.equal(requests.length, 2);
  assert.deepEqual(requests[1], requests[0], 'Explicit retry retains the original key and request');
  await context.close();
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ widths: [360, 1280], policyBeforeGenerationAndRegeneration: true,
    dailyWaitHours: true, resetAutoPosts: 0, explicitRetrySameKey: true, keyboardGeneration: true,
    reducedMotion: true, browserErrors: 0, mockedApiOnly: true }));
} finally { await browser.close(); }
