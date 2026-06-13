import { describe, expect, it } from 'vitest';
import type { Ticket } from '@/api/tickets';
import { boardDropTarget, COLUMN_ORDER } from '../statusOrder';

function ticket(status: Ticket['status']): Ticket {
  return {
    id: 't1',
    key: 'ENG-1',
    title: 'T',
    status,
    priority: 'P2',
    reporterId: 'u1',
    labelIds: [],
    createdAt: '',
    updatedAt: '',
    projectId: 'p1',
    number: 1,
  };
}

describe('boardDropTarget', () => {
  it('returns the target status for a cross-column drop', () => {
    expect(boardDropTarget(ticket('TODO'), 'IN_PROGRESS')).toBe('IN_PROGRESS');
    expect(boardDropTarget(ticket('DONE'), 'TODO')).toBe('TODO'); // any→any legal (no state machine)
  });

  it('returns null for a same-column drop (no-op, AC2c)', () => {
    expect(boardDropTarget(ticket('TODO'), 'TODO')).toBeNull();
  });

  it('returns null for a non-column / missing / non-string over id', () => {
    expect(boardDropTarget(ticket('TODO'), 'NONSENSE')).toBeNull();
    expect(boardDropTarget(ticket('TODO'), null)).toBeNull();
    expect(boardDropTarget(ticket('TODO'), undefined)).toBeNull();
    expect(boardDropTarget(ticket('TODO'), 42)).toBeNull();
  });

  it('COLUMN_ORDER is the four statuses in fixed order', () => {
    expect(COLUMN_ORDER).toEqual(['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE']);
  });
});
