import { afterEach, describe, expect, it, vi } from 'vitest';
import { notificationPollIntervalMs } from '../hooks';

/**
 * D8: the bell polls on Number(VITE_NOTIFICATION_POLL_INTERVAL_MS ?? 30000). The
 * value is read at CALL time (not module load) so these env stubs take effect.
 */
afterEach(() => {
  vi.unstubAllEnvs();
});

describe('notificationPollIntervalMs (env)', () => {
  it('defaults to 30000 when the env var is unset', () => {
    vi.stubEnv('VITE_NOTIFICATION_POLL_INTERVAL_MS', undefined as unknown as string);
    expect(notificationPollIntervalMs()).toBe(30000);
  });

  it('uses a positive configured value', () => {
    vi.stubEnv('VITE_NOTIFICATION_POLL_INTERVAL_MS', '5000');
    expect(notificationPollIntervalMs()).toBe(5000);
  });

  it('falls back to 30000 for a blank value (Number("")===0 would disable polling)', () => {
    vi.stubEnv('VITE_NOTIFICATION_POLL_INTERVAL_MS', '');
    expect(notificationPollIntervalMs()).toBe(30000);
  });

  it('falls back to 30000 for a non-numeric value', () => {
    vi.stubEnv('VITE_NOTIFICATION_POLL_INTERVAL_MS', 'abc');
    expect(notificationPollIntervalMs()).toBe(30000);
  });
});
