import assert from 'node:assert/strict';
import { mkdir, readFile } from 'node:fs/promises';
import { chromium } from 'playwright';

// Local static UI + intercepted synthetic API only: never invoke a candidate provider.
const base = new URL(process.argv[2] ?? 'http://127.0.0.1:15173');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(base.hostname)
  || base.username || base.password || base.pathname !== '/' || base.search || base.hash) throw Error('Use a loopback app origin.');
const fixture = JSON.parse(await readFile(new URL('../contracts/fixtures/snapshot-8.json', import.meta.url), 'utf8'));
const directory = 'reports/local/browser';
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome', headless: true });
const errors = [];
const results = [];
const epoch = new Date('2026-09-20T00:00:00Z');
const flow = page => page.evaluate(() => JSON.parse(localStorage.getItem('worldcup:flow:v1:/')).flow);
const now = page => page.evaluate(() => Date.now());
const visible = (page, state) => page.evaluate(value => {
  // Visibility event semantics, not OS background timer-throttling, are under test.
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => value });
  document.dispatchEvent(new Event('visibilitychange'));
}, state);

function snapshotFor(size) {
  const candidates = Array.from({ length: size }, (_, index) => ({
    ...fixture.candidates[index % fixture.candidates.length],
    id: `synthetic-${index + 1}`, name: `합성 후보 ${index + 1}`, imageUrl: null,
  }));
  return { ...fixture, snapshotId: `round-fixture-${size}`, size, candidates, initialOrder: candidates.map(value => value.id) };
}

async function assertPreparing(page) {
  const current = await flow(page);
  assert.equal(current.play.phase, 'preparation');
  assert.equal(current.play.startedAt, null);
  assert.equal(current.play.deadline, null, 'Round announcement must not consume the seven-second choice deadline.');
  for (const button of await page.getByTestId('candidate-button').all()) assert.equal(await button.isDisabled(), true);
}

async function assertActive(page) {
  const current = await flow(page);
  assert.equal(current.play.phase, 'active');
  assert.equal(current.play.deadline - current.play.startedAt, 7000);
  assert.equal(await page.getByTestId('round-announcement').count(), 0);
  assert.equal(await page.getByTestId('candidate-button').first().isEnabled(), true);
  assert.equal(await page.getByTestId('candidate-button').first().evaluate(element => element === document.activeElement), true);
}

async function assertRoute(page, roundSize, size) {
  const steps = page.locator('ol.round-route li');
  assert.equal(await steps.count(), Math.log2(size));
  const current = page.locator('ol.round-route li[aria-current="step"]');
  assert.equal(await current.count(), 1);
  assert.equal((await current.locator('span').textContent()).trim(), roundSize === 2 ? '결승' : `${roundSize}강`);
  if (roundSize === 4) assert.equal(await current.locator('small').textContent(), '준결승');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
}

try {
  for (const size of [16, 32]) {
    for (const width of [360, 1280]) {
      for (const reducedMotion of ['no-preference', 'reduce']) {
        const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion });
        const snapshot = snapshotFor(size);
        const sessionId = `round-session-${size}`;
        const requests = [];
        await context.route('**/api/v1/**', async route => {
          const request = route.request();
          const pathname = new URL(request.url()).pathname;
          requests.push(pathname);
          assert.equal(pathname, `/api/v1/sessions/${sessionId}/selections`);
          assert.equal(request.method(), 'POST');
          const last = request.postDataJSON().events.at(-1);
          const completed = last.sequence === size - 2;
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nextSequence: last.sequence + 1,
            status: completed ? 'COMPLETED' : 'PLAYING', championId: completed ? last.winnerId : null }) });
        });
        await context.addInitScript(value => {
          if (localStorage.getItem('worldcup:round-fixture-seeded')) return;
          localStorage.setItem('worldcup:round-fixture-seeded', '1');
          // Use virtual time explicitly: init-script ordering with Playwright's Date is unspecified.
          localStorage.setItem('worldcup:flow:v1:/', JSON.stringify({ version: 1, savedAt: value.savedAt, flow: {
            kind: 'play', acknowledged: 0, play: { formatVersion: 1, sessionId: value.sessionId, snapshot: value.snapshot,
              events: [], phase: 'preparation', startedAt: null, deadline: null },
          } }));
        }, { snapshot, sessionId, savedAt: epoch.getTime() });
        const page = await context.newPage();
        page.on('pageerror', error => errors.push(error.message));
        page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
        await page.clock.install({ time: epoch });
        await page.clock.pauseAt(new Date(epoch.getTime() + 1000));
        await page.goto(base.href);
        await page.getByTestId('round-announcement').waitFor();
        await page.getByText('진행을 안전하게 열 수 없어요.').waitFor({ state: 'hidden' });
        const initial = await now(page);
        await assertPreparing(page);
        await assertRoute(page, size, size);
        await page.clock.runFor(450);
        await assertPreparing(page);
        assert.match(await page.getByTestId('round-announcement').textContent(), new RegExp(`${size}강`));
        await page.screenshot({ path: `${directory}/round-start-${size}-${width}-${reducedMotion}.png`, fullPage: true });

        const checksRecovery = size === 16 && width === 360 && reducedMotion === 'no-preference';
        if (checksRecovery) {
          await visible(page, 'hidden');
          await page.clock.runFor(1000);
          await assertPreparing(page);
          // CSS time is independent from the virtual JS clock. Simulate the visual ending
          // while hidden, then verify its final fill state does not hide the restarted gate.
          await page.getByTestId('round-announcement').evaluate(element => {
            for (const animation of element.getAnimations()) animation.finish();
          });
          await visible(page, 'visible');
          const resumed = await now(page);
          assert.equal(await page.getByTestId('round-announcement').evaluate(element => getComputedStyle(element).opacity), '1');
          await page.clock.runFor(450);
          await assertPreparing(page);
          await page.clock.runFor(100);
          await assertActive(page);
          assert((await flow(page)).play.startedAt >= resumed + 500, 'Returning during announcement must grant the full visible announcement.');
          const deadline = (await flow(page)).play.deadline;
          await page.reload();
          await page.getByTestId('screen-play').waitFor();
          await page.getByText('진행을 안전하게 열 수 없어요.').waitFor({ state: 'hidden' });
          assert.equal((await flow(page)).play.deadline, deadline, 'Active round reload must not reset the deadline.');
          assert.equal(await page.getByTestId('round-announcement').count(), 0, 'Active restoration never replays the announcement.');
          await page.clock.runFor(400);
          await assertActive(page);
          assert.equal((await flow(page)).play.deadline, deadline);
        } else {
          await page.clock.runFor(100);
          await assertActive(page);
          assert((await flow(page)).play.startedAt >= initial + 500);
        }

        let sequence = 0;
        const announced = [];
        for (let roundSize = size; roundSize >= 2; roundSize /= 2) {
          announced.push(roundSize);
          for (let roundMatch = 1; roundMatch <= roundSize / 2; roundMatch++) {
            if (sequence > 0) {
              await page.clock.runFor(300); // 280 ms decision feedback, rounded to the 50 ms ticker.
              await assertPreparing(page);
              await assertRoute(page, roundSize, size);
              if (roundMatch === 1) {
                assert.equal(await page.getByTestId('round-announcement').count(), 1);
                assert.match(await page.getByTestId('round-announcement').textContent(), new RegExp(roundSize === 2 ? '결승' : `${roundSize}강`));
                if (reducedMotion === 'reduce') {
                  assert.equal(await page.getByTestId('round-announcement').evaluate(element => getComputedStyle(element).animationName), 'none');
                }
                await page.clock.runFor(450);
                await assertPreparing(page);
                await page.screenshot({ path: `${directory}/round-${size}-to-${roundSize}-${width}-${reducedMotion}.png`, fullPage: true });
                await page.clock.runFor(100);
              } else {
                assert.equal(await page.getByTestId('round-announcement').count(), 0, 'Only the first pair in a round gets an announcement.');
                await page.clock.runFor(400);
              }
              await assertActive(page);
            }
            await assertRoute(page, roundSize, size);
            if (roundSize === 2) {
              const current = await flow(page);
              await page.clock.runFor(current.play.deadline - await now(page));
              await page.clock.runFor(50);
              assert.equal((await flow(page)).play.events.at(-1).reason, 'TIMEOUT_RANDOM', 'Final still uses the same seven-second timeout rule.');
            } else {
              await page.keyboard.press('Enter');
              await page.locator('.winner-stamp').waitFor();
              assert.equal((await flow(page)).play.events.at(-1).reason, 'USER_SELECTED');
            }
            sequence++;
            assert.equal((await flow(page)).play.events.length, sequence);
          }
        }
        await page.clock.runFor(300);
        await page.getByTestId('screen-champion').waitFor();
        await page.getByText('선택 기록과 우승 결과가 저장됐어요.').waitFor();
        assert.equal((await flow(page)).play.events.length, size - 1);
        assert.equal(await page.getByTestId('round-announcement').count(), 0);
        assert(requests.length > 0);
        results.push({ size, width, reducedMotion, announcedRounds: announced, decisions: sequence, sevenSecondDeadline: true });
        await context.close();
      }
    }
  }
  assert.deepEqual(errors, [], 'Browser console or page exceptions');
  console.log(JSON.stringify({ results, announcementMs: 500, hiddenAnnouncementRestarts: true, activeRestorePreservesDeadline: true,
    roundRouteVisible: true, keyboardSelection: true, finalTimeoutUnchanged: true, browserErrors: 0, mockedApiOnly: true }));
} finally { await browser.close(); }
