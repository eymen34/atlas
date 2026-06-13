import { useEffect, useState } from 'react';

/**
 * Returns {@code value} after it has been stable for {@code delayMs}. Used by the search
 * inputs (T-028) so a query fires once the user pauses typing, not on every keystroke.
 * The pending timer is cleared on each change, so rapid input collapses to a single update
 * (tests drive this with {@code vi.useFakeTimers}).
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);

  return debounced;
}
