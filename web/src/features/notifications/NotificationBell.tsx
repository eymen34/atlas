import { Bell } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import type { AppNotification } from '@/api/notifications';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { formatRelativeTime } from '@/lib/relativeTime';
import { cn } from '@/lib/utils';
import { useMarkAll, useMarkRead, useNotificationsQuery, useUnreadBadgeQuery } from './hooks';

/** Human summary line for a notification, by kind. */
function notificationSummary(n: AppNotification): string {
  const who = n.actorDisplayName ?? 'Someone';
  switch (n.kind) {
    case 'ASSIGNED':
      return `${who} assigned ${n.ticketKey} to you`;
    case 'MENTIONED_TICKET':
      return `${who} mentioned you on ${n.ticketKey}`;
    case 'MENTIONED_COMMENT':
      return `${who} mentioned you in a comment on ${n.ticketKey}`;
    case 'WATCHED_STATUS_CHANGED':
      return `${n.ticketKey} moved ${n.fromStatus ?? '?'} → ${n.toStatus ?? '?'}`;
    default:
      return n.ticketKey;
  }
}

/** The bell + unread badge (gate). The full list is mounted only while open. */
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const badge = useUnreadBadgeQuery();
  const unread = badge.data ?? 0;

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Notifications"
          type="button"
          className="relative"
        >
          <Bell className="h-5 w-5" aria-hidden="true" />
          {unread > 0 && (
            <span
              data-testid="notification-badge"
              className="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-white"
            >
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 p-0">
        {/* Mounted only when open → the list query polls only while the panel is visible. */}
        <NotificationPanel onClose={() => setOpen(false)} />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function NotificationPanel({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const list = useNotificationsQuery(true);
  const markRead = useMarkRead();
  const markAll = useMarkAll();
  const items = list.data?.items ?? [];

  function openNotification(n: AppNotification) {
    markRead.mutate(n.id);
    onClose();
    if (n.projectKey && n.ticketKey) {
      navigate(`/projects/${n.projectKey}/tickets/${n.ticketKey}`);
    }
  }

  return (
    <div data-testid="notification-panel">
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <span className="text-sm font-semibold">Notifications</span>
        <button
          type="button"
          className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
          onClick={() => markAll.mutate()}
          disabled={markAll.isPending || items.length === 0}
        >
          Mark all read
        </button>
      </div>
      <div className="max-h-96 overflow-y-auto">
        {list.isLoading ? (
          <p className="px-3 py-6 text-center text-sm text-muted-foreground">Loading…</p>
        ) : items.length === 0 ? (
          <p className="px-3 py-6 text-center text-sm text-muted-foreground">
            You&apos;re all caught up
          </p>
        ) : (
          <ul>
            {items.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  data-testid="notification-row"
                  onClick={() => openNotification(n)}
                  className={cn(
                    'flex w-full flex-col gap-0.5 px-3 py-2 text-left hover:bg-accent',
                    !n.read && 'bg-accent/40'
                  )}
                >
                  <span className="text-sm">{notificationSummary(n)}</span>
                  {n.ticketTitle && (
                    <span className="truncate text-xs text-muted-foreground">{n.ticketTitle}</span>
                  )}
                  {n.createdAt && (
                    <span className="text-[11px] text-muted-foreground">
                      {formatRelativeTime(n.createdAt)}
                    </span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
