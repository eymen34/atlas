import type { Member } from '@/api/projects';
import type { ActivityEvent, Label, Ticket } from '@/api/tickets';

export const TICKET_PROJ_1: Ticket = {
  id: 't-uuid-1',
  key: 'PROJ-1',
  title: 'Fix login bug',
  description: '<p>Repro: open /login and submit empty form.</p>',
  status: 'TODO',
  priority: 'P2',
  assigneeId: 'u1',
  reporterId: 'u1',
  labelIds: ['l1'],
  createdAt: '2026-06-01T10:00:00.000Z',
  updatedAt: '2026-06-01T10:00:00.000Z',
  projectId: 'p-uuid',
  number: 1,
};

export const TICKET_PROJ_1_UPDATED: Ticket = { ...TICKET_PROJ_1, title: 'Login fix' };

export const MEMBERS_TWO: Member[] = [
  { userId: 'u1', email: 'alice@example.com', displayName: 'Alice', role: 'ADMIN', createdAt: '' },
  { userId: 'u2', email: 'bob@example.com', displayName: 'Bob', role: 'MEMBER', createdAt: '' },
];

export const LABELS_TWO: Label[] = [
  { id: 'l1', name: 'bug', color: '#ef4444', projectId: 'p-uuid', createdAt: '' },
  { id: 'l2', name: 'urgent', color: '#f59e0b', projectId: 'p-uuid', createdAt: '' },
];

function evt(over: Partial<ActivityEvent>): ActivityEvent {
  return {
    id: 'a0',
    ticketId: 't-uuid-1',
    eventType: 'CREATED',
    actorId: 'u1',
    createdAt: '2026-06-01T10:00:00.000Z',
    payload: {},
    ...over,
  };
}

export const ACTIVITY_MIXED: ActivityEvent[] = [
  evt({ id: 'a1', eventType: 'CREATED', actorId: 'u1', payload: { title: 'Fix login bug' } }),
  evt({
    id: 'a2',
    eventType: 'STATUS_CHANGED',
    actorId: 'u2',
    payload: { from: 'TODO', to: 'IN_PROGRESS' },
  }),
  evt({ id: 'a3', eventType: 'ASSIGNEE_CHANGED', actorId: 'u1', payload: { to: 'u2' } }),
  // System actor (null) and an actor not in the project (orphan UUID).
  evt({ id: 'a4', eventType: 'LABELS_CHANGED', actorId: null, payload: { added: ['l2'] } }),
  evt({ id: 'a5', eventType: 'PRIORITY_CHANGED', actorId: 'ghost-uuid', payload: { from: 'P2', to: 'P0' } }),
];

export const ACTIVITY_STATUS_CHANGED: ActivityEvent[] = [
  evt({ id: 's1', eventType: 'STATUS_CHANGED', actorId: 'u1', payload: { from: 'TODO', to: 'DONE' } }),
];

/** A future/unknown event type the frontend has never seen. */
export const ACTIVITY_UNKNOWN: ActivityEvent[] = [
  evt({ id: 'x1', eventType: 'COMPLETELY_UNKNOWN_EVENT_XYZ_9999', actorId: 'u1' }),
];
