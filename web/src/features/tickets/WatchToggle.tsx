import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Eye, EyeOff } from 'lucide-react';
import { toast } from 'sonner';
import type { Member } from '@/api/projects';
import { listWatchers, ticketKeys, unwatchTicket, watchTicket } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { useActorLookup } from '@/hooks/useActorLookup';
import { usePublicConfig } from '@/hooks/usePublicConfig';
import { useAuthStore } from '@/store/authStore';

export interface WatchToggleProps {
  /** Ticket UUID (the watcher endpoints bind @PathVariable UUID — never the key). */
  ticketId: string;
  /** Project members, used to resolve watcher ids to names in the tooltip. */
  members: Member[];
}

/**
 * GATE (T-023): the ONLY hook here is {@link usePublicConfig}; the loading /
 * flag-off early-returns therefore never violate the rules of hooks. When the
 * watchers feature is on, it mounts {@link WatchToggleInner} (which holds all the
 * data hooks). When off it returns null — the toggle is ABSENT, not disabled, and
 * the watcher list is never fetched.
 */
export function WatchToggle({ ticketId, members }: WatchToggleProps) {
  const { config, isLoading } = usePublicConfig();
  if (isLoading) {
    return null;
  }
  if (!config?.features.watchers) {
    return null;
  }
  return <WatchToggleInner ticketId={ticketId} members={members} />;
}

function WatchToggleInner({ ticketId, members }: WatchToggleProps) {
  const me = useAuthStore((s) => s.user?.id);
  const queryClient = useQueryClient();
  const lookup = useActorLookup(members);

  const { data: watchers = [] } = useQuery({
    queryKey: ticketKeys.watchers(ticketId),
    queryFn: () => listWatchers(ticketId),
  });

  const isWatching = !!me && watchers.includes(me);

  const mutation = useMutation({
    mutationFn: () => (isWatching ? unwatchTicket(ticketId) : watchTicket(ticketId)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.watchers(ticketId) }),
    onError: () => toast.error(isWatching ? 'Failed to unwatch' : 'Failed to watch'),
  });

  const names = watchers.map((uid) => lookup(uid).name).join(', ');
  const Icon = isWatching ? EyeOff : Eye;

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-label={isWatching ? 'Unwatch ticket' : 'Watch ticket'}
            data-testid="watch-toggle"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            <Icon className="size-4" />
            <span>{watchers.length}</span>
          </Button>
        </TooltipTrigger>
        <TooltipContent>{names || 'No watchers'}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
