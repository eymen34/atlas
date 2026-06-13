import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebouncedValue } from './useDebouncedValue';

describe('useDebouncedValue', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('returns the initial value immediately', () => {
    const { result } = renderHook(() => useDebouncedValue('a', 250));
    expect(result.current).toBe('a');
  });

  it('collapses rapid changes into a single trailing update', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 250), {
      initialProps: { v: 'a' },
    });
    rerender({ v: 'ab' });
    rerender({ v: 'abc' });
    rerender({ v: 'abcd' });

    // Before the delay elapses, the debounced value is still the initial one.
    expect(result.current).toBe('a');

    act(() => vi.advanceTimersByTime(250));
    expect(result.current).toBe('abcd');
  });

  it('resets the timer on each change (only fires after a full quiet window)', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 250), {
      initialProps: { v: 'x' },
    });
    rerender({ v: 'y' });
    act(() => vi.advanceTimersByTime(200)); // 200ms < 250ms → no update yet
    rerender({ v: 'z' }); // resets the timer
    act(() => vi.advanceTimersByTime(200)); // 400ms total, but only 200ms since 'z'
    expect(result.current).toBe('x');

    act(() => vi.advanceTimersByTime(50)); // now 250ms since 'z'
    expect(result.current).toBe('z');
  });
});
