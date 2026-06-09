import type { ActivityEvent } from '@/api/tickets';
import type { Member } from '@/api/projects';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { formatRelativeTime } from '@/lib/relativeTime';
import { getEventMeta, makeActorLookup } from './activityMeta';

export interface TicketActivityTimelineProps {
  events: ActivityEvent[];
  members: Member[];
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Activity timeline: one row per event with the event icon, the actor's avatar +
 * name, a human-readable summary (UUIDs resolved to names), and a relative
 * timestamp. Unknown/future event types fall back to a generic icon + summary
 * via {@link getEventMeta}, so a new backend event type never crashes the page.
 */
export function TicketActivityTimeline({ events, members }: TicketActivityTimelineProps) {
  const lookup = makeActorLookup(members);

  return (
    <section
      aria-label="Activity timeline"
      data-testid="ticket-activity-timeline"
      className="space-y-3"
    >
      <h2 className="text-sm font-medium text-muted-foreground">Activity</h2>
      {events.length === 0 ? (
        <p data-testid="activity-empty-state" className="text-sm text-muted-foreground">
          No activity yet.
        </p>
      ) : (
        <ul className="space-y-4">
          {events.map((event) => {
            const meta = getEventMeta(event.eventType);
            const Icon = meta.Icon;
            const actor = lookup(event.actorId);
            return (
              <li
                key={event.id || `${event.eventType}-${event.createdAt}`}
                data-testid={`activity-event-${event.eventType}`}
                className="flex items-start gap-3 text-sm"
              >
                <span
                  data-testid="event-icon"
                  className="mt-1 text-muted-foreground"
                  aria-hidden="true"
                >
                  <Icon className="size-4" />
                </span>
                <Avatar size="sm" className="mt-0.5">
                  {actor.avatarUrl ? <AvatarImage src={actor.avatarUrl} alt="" /> : null}
                  <AvatarFallback>{initials(actor.name)}</AvatarFallback>
                </Avatar>
                <div className="min-w-0 flex-1">
                  <p className="leading-snug">
                    <span className="font-medium">{actor.name}</span> {meta.summary(event, lookup)}
                  </p>
                  <time
                    data-testid="event-timestamp"
                    dateTime={event.createdAt}
                    className="text-xs text-muted-foreground"
                  >
                    {formatRelativeTime(event.createdAt)}
                  </time>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
