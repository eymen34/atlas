import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { TicketFilters } from '@/api/tickets';
import { FilterBar } from '../list/FilterBar';

const filters: TicketFilters = { page: 0, size: 25 };

describe('FilterBar', () => {
  it('renders the Status control by default (list)', () => {
    render(<FilterBar value={filters} onChange={vi.fn()} members={[]} labels={[]} />);
    expect(screen.getByRole('button', { name: /Status/ })).toBeInTheDocument();
  });

  it('hides the Status control when hideStatus is set (board)', () => {
    render(<FilterBar value={filters} onChange={vi.fn()} members={[]} labels={[]} hideStatus />);
    expect(screen.queryByRole('button', { name: /Status/ })).toBeNull();
    // The other filters remain.
    expect(screen.getByRole('button', { name: /Priority/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Assignee/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Labels/ })).toBeInTheDocument();
  });
});
