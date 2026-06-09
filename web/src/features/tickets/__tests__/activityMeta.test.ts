import { describe, expect, it } from 'vitest';
import type { ActivityEvent, ActivityEventType } from '@/api/tickets';
import { EVENT_TYPE_META, getEventMeta, makeActorLookup } from '../activityMeta';
import { MEMBERS_TWO } from './fixtures';

const KNOWN_TYPES: ActivityEventType[] = [
  'CREATED',
  'STATUS_CHANGED',
  'ASSIGNEE_CHANGED',
  'PRIORITY_CHANGED',
  'LABELS_CHANGED',
  'COMMENT_ADDED',
  'COMMENT_EDITED',
  'COMMENT_DELETED',
  'ATTACHMENT_ADDED',
  'ATTACHMENT_REMOVED',
  'LINK_ADDED',
  'LINK_REMOVED',
];

function event(over: Partial<ActivityEvent>): ActivityEvent {
  return { id: 'a', ticketId: 't', eventType: 'CREATED', actorId: 'u1', createdAt: '', payload: {}, ...over };
}

const lookup = makeActorLookup(MEMBERS_TWO);

describe('getEventMeta', () => {
  it('provides an icon + summary for all 12 known event types', () => {
    for (const type of KNOWN_TYPES) {
      const meta = getEventMeta(type);
      expect(meta).toBe(EVENT_TYPE_META[type]);
      expect(meta.Icon).toBeTruthy();
      expect(typeof meta.summary).toBe('function');
    }
  });

  it('falls back to the UNKNOWN meta for an unrecognized type without throwing', () => {
    const meta = getEventMeta('TOTALLY_NEW_EVENT_2099');
    expect(meta).toBe(EVENT_TYPE_META.UNKNOWN);
    expect(meta.summary(event({ eventType: 'TOTALLY_NEW_EVENT_2099' }), lookup)).toBe(
      'updated this ticket'
    );
  });

  it('builds human-readable summaries and never emits a raw UUID', () => {
    expect(getEventMeta('CREATED').summary(event({}), lookup)).toBe('created this ticket');
    expect(
      getEventMeta('STATUS_CHANGED').summary(
        event({ payload: { from: 'TODO', to: 'DONE' } }),
        lookup
      )
    ).toBe('changed status from TODO to DONE');
    // Assignee target UUID resolves to a name.
    expect(
      getEventMeta('ASSIGNEE_CHANGED').summary(event({ payload: { to: 'u2' } }), lookup)
    ).toBe('assigned this ticket to Bob');
    expect(getEventMeta('ASSIGNEE_CHANGED').summary(event({ payload: {} }), lookup)).toBe(
      'unassigned this ticket'
    );
    expect(
      getEventMeta('PRIORITY_CHANGED').summary(event({ payload: { from: 'P2', to: 'P0' } }), lookup)
    ).toBe('changed priority from P2 to P0');
  });
});

describe('makeActorLookup', () => {
  it('resolves a known member id to its display name', () => {
    expect(lookup('u1')).toEqual({ name: 'Alice', avatarUrl: undefined });
  });

  it('maps null → System, undefined → Unknown user, orphan id → Unknown user', () => {
    expect(lookup(null)).toEqual({ name: 'System' });
    expect(lookup(undefined)).toEqual({ name: 'Unknown user' });
    expect(lookup('not-a-member')).toEqual({ name: 'Unknown user' });
  });
});
