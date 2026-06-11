import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationPage,
  notificationKeys,
} from '@/api/notifications';

/**
 * T-024 notification hooks.
 *
 * Two independent polls back the bell: a cheap unread BADGE query
 * ({@code unread=true, size=1} → {@code total}) that always runs, and the full
 * LIST query that runs only while the dropdown is open (gated by {@code enabled}).
 * Both poll on {@link notificationPollIntervalMs} (env-tunable, default 30s, D8).
 */

/**
 * Resolved at call time (not module load) so tests can {@code vi.stubEnv} it.
 * D8: {@code Number(VITE_NOTIFICATION_POLL_INTERVAL_MS ?? 30000)}, with a guard so
 * an unset/blank/invalid value falls back to 30s rather than 0 (which disables polling).
 */
export function notificationPollIntervalMs(): number {
  const value = Number(import.meta.env.VITE_NOTIFICATION_POLL_INTERVAL_MS ?? 30000);
  return Number.isFinite(value) && value > 0 ? value : 30000;
}

/** Full notification list (newest first). Gated by {@code enabled} so it only polls while open. */
export function useNotificationsQuery(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.list(false),
    queryFn: () => listNotifications(false, 0, 20),
    refetchInterval: notificationPollIntervalMs(),
    enabled,
  });
}

/** Unread count for the badge — second query (BLOCKING-1: no count endpoint). */
export function useUnreadBadgeQuery() {
  return useQuery({
    queryKey: notificationKeys.badge(),
    queryFn: () => listNotifications(true, 0, 1),
    refetchInterval: notificationPollIntervalMs(),
    select: (page: NotificationPage) => page.total,
  });
}

/** Mark one notification read; invalidates BOTH the list and the badge. */
export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}

/** Mark all notifications read; invalidates BOTH the list and the badge. */
export function useMarkAll() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}
