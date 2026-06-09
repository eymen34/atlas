import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { CreateTicketRequest } from '@/api/generated';
import { apiErrorMessage } from '@/api/errors';
import type { Member } from '@/api/projects';
import { createTicket, ticketKeys } from '@/api/tickets';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { TipTapDescriptionEditor } from './TipTapDescriptionEditor';

/** Sentinel for the assignee select; the key is OMITTED from the request when chosen (D5). */
const UNASSIGNED = 'unassigned';
const PRIORITIES = ['P0', 'P1', 'P2', 'P3'] as const;

export interface NewTicketDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: string;
  projectKey: string;
  members: Member[];
}

/**
 * New-ticket dialog (T-020, pragmatic per D1). The form state lives in a child
 * ({@link NewTicketForm}) rendered inside DialogContent, which Radix unmounts on
 * close — so the form resets to its defaults on every open via remount, with no
 * reset effect.
 */
export function NewTicketDialog({
  open,
  onOpenChange,
  projectId,
  projectKey,
  members,
}: NewTicketDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <NewTicketForm
          projectId={projectId}
          projectKey={projectKey}
          members={members}
          onClose={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  );
}

interface NewTicketFormProps {
  projectId: string;
  projectKey: string;
  members: Member[];
  onClose: () => void;
}

/**
 * The create-ticket form. On a successful POST it invalidates the project's ticket
 * lists, toasts the new key, and closes — no optimistic prepend (D1). Labels are
 * NOT part of create (the backend CreateTicketRequest has no labelIds field; labels
 * are managed via the separate PUT endpoint).
 *
 * <p>D5: when no assignee is chosen the assigneeId key is omitted from the request
 * body entirely (the 'unassigned' sentinel is a UI-only value, never sent).
 */
function NewTicketForm({ projectId, projectKey, members, onClose }: NewTicketFormProps) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState<CreateTicketRequest.priority>(
    CreateTicketRequest.priority.P2
  );
  const [assigneeId, setAssigneeId] = useState<string | null>(null);
  const [titleError, setTitleError] = useState(false);

  const mutation = useMutation({
    mutationFn: (req: CreateTicketRequest) => createTicket(projectId, req),
    onSuccess: async (ticket) => {
      await queryClient.invalidateQueries({ queryKey: ticketKeys.lists(projectId) });
      toast.success(`${ticket.key} created`);
      onClose();
    },
    onError: (err) => {
      toast.error(apiErrorMessage(err, 'Could not create the ticket. Please try again.'));
    },
  });

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (title.trim().length === 0) {
      setTitleError(true);
      return;
    }
    const req: CreateTicketRequest = {
      title: title.trim(),
      description: description.trim() ? description : undefined,
      priority,
      // D5: omit assigneeId entirely when unassigned (never send the sentinel string).
      ...(assigneeId !== null ? { assigneeId } : {}),
    };
    mutation.mutate(req);
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>New ticket</DialogTitle>
        <DialogDescription>Create a ticket in {projectKey}.</DialogDescription>
      </DialogHeader>
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="ticket-title">Title</Label>
          <Input
            id="ticket-title"
            value={title}
            aria-invalid={titleError ? 'true' : undefined}
            onChange={(e) => {
              setTitle(e.target.value);
              if (titleError) setTitleError(false);
            }}
          />
          {titleError && (
            <p role="alert" className="text-sm text-destructive">
              Title is required
            </p>
          )}
        </div>

        <div className="space-y-1">
          {/* The editor is a contenteditable region, not a form control, so Enter
              inside it never triggers implicit form submission (EC-15). */}
          <Label htmlFor="ticket-description">Description</Label>
          <TipTapDescriptionEditor value={description} onChange={setDescription} />
        </div>

        <div className="flex gap-4">
          <div className="space-y-1">
            <Label htmlFor="ticket-priority">Priority</Label>
            <Select
              value={priority}
              onValueChange={(v) => setPriority(v as CreateTicketRequest.priority)}
            >
              <SelectTrigger id="ticket-priority" className="w-[120px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PRIORITIES.map((p) => (
                  <SelectItem key={p} value={p}>
                    {p}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1">
            <Label htmlFor="ticket-assignee">Assignee</Label>
            <Select
              value={assigneeId ?? UNASSIGNED}
              onValueChange={(v) => setAssigneeId(v === UNASSIGNED ? null : v)}
            >
              <SelectTrigger id="ticket-assignee" className="w-[200px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={UNASSIGNED}>Unassigned</SelectItem>
                {members.map((m) => (
                  <SelectItem key={m.userId} value={m.userId}>
                    {m.displayName || m.email}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            Create
          </Button>
        </DialogFooter>
      </form>
    </>
  );
}
