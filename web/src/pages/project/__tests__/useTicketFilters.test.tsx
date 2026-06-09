import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { useTicketFilters } from '@/pages/project/list/useTicketFilters';

function wrapperFor(search: string) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <MemoryRouter initialEntries={[`/projects/ALPHA/list${search}`]}>{children}</MemoryRouter>;
  };
}

function parse(search: string) {
  const { result } = renderHook(() => useTicketFilters(), { wrapper: wrapperFor(search) });
  return result.current[0];
}

describe('useTicketFilters — URL parsing', () => {
  it('reads the exact backend param names with repeated multi-value keys', () => {
    const f = parse('?status=TODO&status=DONE&priority=P0&label=l1&label=l2&assigneeId=u-1&page=2&size=50');
    expect(f.status).toEqual(['TODO', 'DONE']);
    expect(f.priority).toEqual(['P0']);
    expect(f.label).toEqual(['l1', 'l2']);
    expect(f.assigneeId).toBe('u-1');
    expect(f.page).toBe(2);
    expect(f.size).toBe(50);
  });

  it('preserves the "unassigned" sentinel as the assigneeId (D5 filter side)', () => {
    expect(parse('?assigneeId=unassigned').assigneeId).toBe('unassigned');
  });

  it('EC-12: drops unknown enum values, keeping only valid ones', () => {
    const f = parse('?status=BOGUS&status=TODO&priority=P9');
    expect(f.status).toEqual(['TODO']);
    expect(f.priority).toBeUndefined(); // P9 invalid → empty → undefined
  });

  it('EC-16c: clamps an oversized page size to the backend max (100)', () => {
    expect(parse('?size=999').size).toBe(100);
  });

  it('EC-16d: falls back to the default size for an out-of-set value', () => {
    expect(parse('?size=7').size).toBe(25);
  });

  it('EC-16: coerces a negative / non-integer page to 0', () => {
    expect(parse('?page=-1').page).toBe(0);
    expect(parse('?page=abc').page).toBe(0);
    expect(parse('?page=3').page).toBe(3);
  });

  it('defaults to page 0 / size 25 when params are absent', () => {
    const f = parse('');
    expect(f).toMatchObject({ page: 0, size: 25 });
    expect(f.status).toBeUndefined();
    expect(f.assigneeId).toBeUndefined();
  });
});
