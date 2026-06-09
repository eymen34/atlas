import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PagerFooter } from '@/pages/project/list/PagerFooter';

describe('PagerFooter — zero-based paging (AC-3.3)', () => {
  it('shows "No tickets" and disables both buttons when total is 0', () => {
    render(<PagerFooter page={0} size={25} total={0} onChange={vi.fn()} />);
    expect(screen.getByTestId('pager-summary')).toHaveTextContent('No tickets');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('on the first page: Previous disabled, Next enabled, correct range', () => {
    render(<PagerFooter page={0} size={25} total={100} onChange={vi.fn()} />);
    expect(screen.getByTestId('pager-summary')).toHaveTextContent('Showing 1–25 of 100');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
  });

  it('on the last page: Next disabled, Previous enabled, range ends at total', () => {
    render(<PagerFooter page={3} size={25} total={100} onChange={vi.fn()} />);
    expect(screen.getByTestId('pager-summary')).toHaveTextContent('Showing 76–100 of 100');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('Next advances the zero-based page; Previous goes back', () => {
    const onChange = vi.fn();
    render(<PagerFooter page={1} size={25} total={100} onChange={onChange} />);
    screen.getByRole('button', { name: 'Next' }).click();
    expect(onChange).toHaveBeenCalledWith(2, 25);
    screen.getByRole('button', { name: 'Previous' }).click();
    expect(onChange).toHaveBeenCalledWith(0, 25);
  });
});
