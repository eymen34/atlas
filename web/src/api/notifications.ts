import {
  type NotificationResponse,
  NotificationsService,
  type PagedResponseNotificationResponse,
} from './generated';

/**
 * T-024 app-facing in-app-notification API, mirroring tickets.ts / projects.ts.
 *
 * The generated client models every field as optional and emits {@code kind} as a
 * namespaced string enum. We derive the app-facing {@link NotificationKind} as the
 * LITERAL union (template-literal over the generated enum) so it tracks codegen.
 * Components and hooks go through these wrappers, never the generated service.
 *
 * There is deliberately NO unread-count call: the bell's badge reuses the list with
 * {@code unread=true, size=1} and reads {@code total} (BLOCKING-1).
 */
export type NotificationKind = `${NonNullable<NotificationResponse['kind']>}`;

/** Renamed from the wire `Notification` to avoid shadowing the DOM `Notification` global. */
export interface AppNotification {
  id: string;
  kind: NotificationKind;
  ticketId: string;
  /** Display key, e.g. "ENG-1". */
  ticketKey: string;
  ticketTitle: string;
  /** Project key, e.g. "ENG" — combined with {@link ticketKey} for the row link. */
  projectKey: string;
  actorId?: string;
  actorDisplayName?: string;
  commentId?: string;
  fromStatus?: string;
  toStatus?: string;
  read: boolean;
  createdAt: string;
}

export interface NotificationPage {
  items: AppNotification[];
  page: number;
  size: number;
  total: number;
}

/** Maps a generated NotificationResponse; throws on a malformed row (toTicket precedent). */
export function toNotification(r: NotificationResponse): AppNotification {
  if (!r.id) {
    throw new Error('Malformed notification response: missing id');
  }
  return {
    id: r.id,
    kind: (r.kind ? String(r.kind) : 'ASSIGNED') as NotificationKind,
    ticketId: r.ticketId ?? '',
    ticketKey: r.ticketKey ?? '',
    ticketTitle: r.ticketTitle ?? '',
    projectKey: r.projectKey ?? '',
    actorId: r.actorId,
    actorDisplayName: r.actorDisplayName,
    commentId: r.commentId,
    fromStatus: r.fromStatus,
    toStatus: r.toStatus,
    read: r.read ?? false,
    createdAt: r.createdAt ?? '',
  };
}

/**
 * TanStack Query keys. The full list and the unread badge use DISTINCT keys (so
 * the 30s list poll and the size-1 badge poll cache independently) but share the
 * {@link notificationKeys.all} prefix so a mark-read mutation can invalidate both
 * with one call.
 */
export const notificationKeys = {
  all: ['notifications'] as const,
  list: (unread: boolean) => [...notificationKeys.all, 'list', { unread }] as const,
  badge: () => [...notificationKeys.all, 'badge'] as const,
};

export async function listNotifications(
  unread?: boolean,
  page = 0,
  size = 20
): Promise<NotificationPage> {
  const res: PagedResponseNotificationResponse = await NotificationsService.listNotifications(
    unread,
    page,
    size
  );
  return {
    items: (res.items ?? []).map(toNotification),
    page: res.page ?? page,
    size: res.size ?? size,
    total: res.total ?? 0,
  };
}

/** Idempotent mark-read (204; 404 if the id is foreign or unknown). */
export async function markNotificationRead(id: string): Promise<void> {
  await NotificationsService.markNotificationRead(id);
}

/** Idempotent mark-all-read (204). */
export async function markAllNotificationsRead(): Promise<void> {
  await NotificationsService.markAllNotificationsRead();
}
