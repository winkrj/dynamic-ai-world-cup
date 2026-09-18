import test from 'node:test';
import assert from 'node:assert/strict';
import { formatRetryDuration } from '../src/ui/retry-duration.ts';

test('retry durations preserve minute/second precision and show hours for daily limits', () => {
  for (const [seconds, expected] of [
    [0, '0분 00초'],
    [1, '0분 01초'],
    [59, '0분 59초'],
    [60, '1분 00초'],
    [600, '10분 00초'],
    [3599, '59분 59초'],
    [3600, '1시간 00분 00초'],
    [3661, '1시간 01분 01초'],
    [86399, '23시간 59분 59초'],
    [86400, '24시간 00분 00초'],
  ] as const) assert.equal(formatRetryDuration(seconds), expected);
});

test('retry duration rounds up positive fractions and does not show negative waits', () => {
  assert.equal(formatRetryDuration(0.001), '0분 01초');
  assert.equal(formatRetryDuration(3599.1), '1시간 00분 00초');
  assert.equal(formatRetryDuration(-1), '0분 00초');
});
