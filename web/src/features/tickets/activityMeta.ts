import {
  Activity,
  ArrowLeftRight,
  Flag,
  Link,
  type LucideIcon,
  MessageSquare,
  Paperclip,
  Pencil,
  Plus,
  Tag,
  Trash2,
  Unlink,
  User,
} from 'lucide-react';
import type { ActivityEvent, ActivityEventType } from '@/api/tickets';
import type { Member } from '@/api/projects';

/** Resolved actor for display: a name (never a raw UUID) and an optional avatar. */
export interface ActorInfo {
  name: string;
  avatarUrl?: string;
}

/**
 * Resolves an event's actor to a display name + avatar:
 * - `null`  → "System" (a system-generated event with no human actor),
 * - missing → "Unknown user" (actor omitted on the wire),
 * - a UUID  → the member's name, or "Unknown user" if not in the project.
 */
export type ActorLookup = (actorId?: string | null) => ActorInfo;

export function makeActorLookup(members: Member[]): ActorLookup {
  const byId = new Map(members.map((m) => [m.userId, m]));
  return (actorId) => {
    if (actorId === null) return { name: 'System' };
    if (actorId === undefined) return { name: 'Unknown user' };
    const m = byId.get(actorId);
    return m
      ? { name: m.displayName || m.email || 'Unknown user', avatarUrl: m.avatarUrl }
      : { name: 'Unknown user' };
  };
}

export interface EventMeta {
  Icon: LucideIcon;
  /** Action phrase (no leading actor). Resolves any UUID via lookup — never raw. */
  summary: (event: ActivityEvent, lookup: ActorLookup) => string;
}

/** Reads a non-empty string field from an event payload, else undefined. */
function payloadStr(payload: Record<string, unknown>, key: string): string | undefined {
  const v = payload[key];
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

/**
 * Metadata for every known activity event type plus an UNKNOWN fallback so a
 * future/unrecognized eventType from the backend renders safely instead of
 * crashing. Summaries are deliberately terse and resolve UUIDs through the actor
 * lookup so a raw id is never shown to the user.
 */
export const EVENT_TYPE_META: Record<ActivityEventType | 'UNKNOWN', EventMeta> = {
  CREATED: { Icon: Plus, summary: () => 'created this ticket' },
  STATUS_CHANGED: {
    Icon: ArrowLeftRight,
    summary: (e) => {
      const from = payloadStr(e.payload, 'from');
      const to = payloadStr(e.payload, 'to');
      return from && to ? `changed status from ${from} to ${to}` : 'changed the status';
    },
  },
  ASSIGNEE_CHANGED: {
    Icon: User,
    summary: (e, lookup) => {
      const to = payloadStr(e.payload, 'to');
      return to ? `assigned this ticket to ${lookup(to).name}` : 'unassigned this ticket';
    },
  },
  PRIORITY_CHANGED: {
    Icon: Flag,
    summary: (e) => {
      const from = payloadStr(e.payload, 'from');
      const to = payloadStr(e.payload, 'to');
      return from && to ? `changed priority from ${from} to ${to}` : 'changed the priority';
    },
  },
  LABELS_CHANGED: { Icon: Tag, summary: () => 'updated the labels' },
  COMMENT_ADDED: { Icon: MessageSquare, summary: () => 'added a comment' },
  COMMENT_EDITED: { Icon: Pencil, summary: () => 'edited a comment' },
  COMMENT_DELETED: { Icon: Trash2, summary: () => 'deleted a comment' },
  ATTACHMENT_ADDED: { Icon: Paperclip, summary: () => 'added an attachment' },
  ATTACHMENT_REMOVED: { Icon: Paperclip, summary: () => 'removed an attachment' },
  LINK_ADDED: { Icon: Link, summary: () => 'added a link' },
  LINK_REMOVED: { Icon: Unlink, summary: () => 'removed a link' },
  UNKNOWN: { Icon: Activity, summary: () => 'updated this ticket' },
};

/** Never throws: an unknown/future eventType maps to the UNKNOWN fallback meta. */
export function getEventMeta(eventType: string): EventMeta {
  return (
    (EVENT_TYPE_META as Record<string, EventMeta | undefined>)[eventType] ?? EVENT_TYPE_META.UNKNOWN
  );
}
