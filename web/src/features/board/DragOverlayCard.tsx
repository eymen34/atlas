import { GripVertical } from 'lucide-react';
import type { Ticket } from '@/api/tickets';
import { Badge } from '@/components/ui/badge';

/** Visual-only clone shown under the cursor while dragging — no Link, no listeners. */
export function DragOverlayCard({
  ticket,
  assigneeName,
  labelColors,
}: {
  ticket: Ticket;
  assigneeName?: string;
  labelColors: string[];
}) {
  return (
    <div className="flex items-start gap-2 rounded-md border border-border bg-card p-2 text-sm shadow-lg">
      <span className="mt-0.5 text-muted-foreground">
        <GripVertical className="h-4 w-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <span className="font-mono text-xs text-muted-foreground">{ticket.key}</span>
        <p className="truncate font-medium">{ticket.title}</p>
        <div className="mt-1 flex items-center gap-2">
          <Badge variant="outline" className="font-normal">
            {ticket.priority}
          </Badge>
          {assigneeName && (
            <span className="truncate text-xs text-muted-foreground">{assigneeName}</span>
          )}
          {labelColors.length > 0 && (
            <span className="flex items-center gap-1" aria-hidden="true">
              {labelColors.map((color, i) => (
                <span
                  key={i}
                  className="h-2 w-2 rounded-full border border-border"
                  style={{ backgroundColor: color }}
                />
              ))}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
