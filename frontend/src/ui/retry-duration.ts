/** Display only: the server's retryAt still determines when a manual retry is enabled. */
export function formatRetryDuration(remainingSeconds: number): string {
  const seconds = Math.max(0, Math.ceil(remainingSeconds));
  const pad = (value: number) => String(value).padStart(2, '0');
  if (seconds >= 3600) {
    return `${Math.floor(seconds / 3600)}시간 ${pad(Math.floor(seconds / 60) % 60)}분 ${pad(seconds % 60)}초`;
  }
  return `${Math.floor(seconds / 60)}분 ${pad(seconds % 60)}초`;
}
