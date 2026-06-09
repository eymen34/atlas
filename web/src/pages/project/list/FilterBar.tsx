import {
  PRIORITY_OPTIONS,
  STATUS_OPTIONS,
  type Label,
  type TicketFilters,
  type TicketPriority,
  type TicketStatus,
} from '@/api/tickets';
import type { Member } from '@/api/projects';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

const UNASSIGNED = 'unassigned';

export interface FilterBarProps {
  value: TicketFilters;
  onChange: (next: TicketFilters) => void;
  members: Member[];
  labels: Label[];
}

/**
 * Filter controls for the ticket list. Status/Priority/Labels are multi-select
 * checkbox dropdowns; Assignee is a single-select radio dropdown whose first
 * option is the "Unassigned" sentinel ('unassigned', forwarded to the backend).
 * EVERY control change resets the page to 0 (the URL writer preserves page only
 * for the pager).
 */
export function FilterBar({ value, onChange, members, labels }: FilterBarProps) {
  function toggleStatus(status: TicketStatus, checked: boolean) {
    const current = value.status ?? [];
    const next = checked ? [...current, status] : current.filter((s) => s !== status);
    onChange({ ...value, status: next.length > 0 ? next : undefined, page: 0 });
  }

  function togglePriority(priority: TicketPriority, checked: boolean) {
    const current = value.priority ?? [];
    const next = checked ? [...current, priority] : current.filter((p) => p !== priority);
    onChange({ ...value, priority: next.length > 0 ? next : undefined, page: 0 });
  }

  function toggleLabel(labelId: string, checked: boolean) {
    const current = value.label ?? [];
    const next = checked ? [...current, labelId] : current.filter((l) => l !== labelId);
    onChange({ ...value, label: next.length > 0 ? next : undefined, page: 0 });
  }

  function setAssignee(next: string) {
    onChange({ ...value, assigneeId: next === '' ? undefined : next, page: 0 });
  }

  const statusCount = value.status?.length ?? 0;
  const priorityCount = value.priority?.length ?? 0;
  const labelCount = value.label?.length ?? 0;

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="filter-bar">
      {/* Status */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            Status{statusCount > 0 ? ` (${statusCount})` : ''}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuLabel>Status</DropdownMenuLabel>
          {STATUS_OPTIONS.map((status) => (
            <DropdownMenuCheckboxItem
              key={status}
              checked={value.status?.includes(status) ?? false}
              onCheckedChange={(checked) => toggleStatus(status, checked === true)}
              onSelect={(e) => e.preventDefault()}
            >
              {status}
            </DropdownMenuCheckboxItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* Priority */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            Priority{priorityCount > 0 ? ` (${priorityCount})` : ''}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuLabel>Priority</DropdownMenuLabel>
          {PRIORITY_OPTIONS.map((priority) => (
            <DropdownMenuCheckboxItem
              key={priority}
              checked={value.priority?.includes(priority) ?? false}
              onCheckedChange={(checked) => togglePriority(priority, checked === true)}
              onSelect={(e) => e.preventDefault()}
            >
              {priority}
            </DropdownMenuCheckboxItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* Assignee */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            Assignee{value.assigneeId ? ' (1)' : ''}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="max-h-72 overflow-y-auto">
          <DropdownMenuLabel>Assignee</DropdownMenuLabel>
          <DropdownMenuRadioGroup value={value.assigneeId ?? ''} onValueChange={setAssignee}>
            <DropdownMenuRadioItem value="">Anyone</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value={UNASSIGNED}>Unassigned</DropdownMenuRadioItem>
            <DropdownMenuSeparator />
            {members.map((m) => (
              <DropdownMenuRadioItem key={m.userId} value={m.userId}>
                {m.displayName || m.email}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>

      {/* Labels */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            Labels{labelCount > 0 ? ` (${labelCount})` : ''}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="max-h-72 overflow-y-auto">
          <DropdownMenuLabel>Labels</DropdownMenuLabel>
          {labels.length === 0 && (
            <p className="px-2 py-1.5 text-sm text-muted-foreground">No labels</p>
          )}
          {labels.map((label) => (
            <DropdownMenuCheckboxItem
              key={label.id}
              checked={value.label?.includes(label.id) ?? false}
              onCheckedChange={(checked) => toggleLabel(label.id, checked === true)}
              onSelect={(e) => e.preventDefault()}
            >
              {label.name}
            </DropdownMenuCheckboxItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
