import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  apiErrorMessage,
  changeMemberRole,
  type Member,
  type ProjectRole,
  projectKeys,
  removeMember,
} from '@/api/projects';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

/**
 * Renders a project's members. With {@code readOnly} (the default for the
 * non-admin Members page) only role badges show; admins get an inline role
 * selector and a Remove button. Every mutation invalidates BOTH the member list
 * (keyed by the project UUID) and the project detail (keyed by the URL segment)
 * so the memberCount badge in the header stays fresh.
 *
 * {@code projectId} is the project UUID (member endpoints require a UUID, not a
 * key); {@code idOrKey} is the URL segment used as the detail-query cache key.
 */
export function MemberList({
  projectId,
  idOrKey,
  members,
  readOnly = true,
}: {
  projectId: string;
  idOrKey: string;
  members: Member[];
  readOnly?: boolean;
}) {
  const queryClient = useQueryClient();

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: projectKeys.members(projectId) });
    void queryClient.invalidateQueries({ queryKey: projectKeys.detail(idOrKey) });
  }

  const changeRole = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: ProjectRole }) =>
      changeMemberRole(projectId, userId, role),
    onSuccess: invalidate,
    onError: (err) => toast.error(apiErrorMessage(err, 'Could not change the role.')),
  });

  const remove = useMutation({
    mutationFn: (userId: string) => removeMember(projectId, userId),
    onSuccess: invalidate,
    onError: (err) => toast.error(apiErrorMessage(err, 'Could not remove the member.')),
  });

  const busy = changeRole.isPending || remove.isPending;

  return (
    <ul className="divide-y rounded-md border" data-testid="member-list">
      {members.map((member) => (
        <li key={member.userId} className="flex items-center justify-between gap-4 px-4 py-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium">{member.displayName}</p>
            <p className="truncate text-xs text-muted-foreground">{member.email}</p>
          </div>
          {readOnly ? (
            <Badge variant={member.role === 'ADMIN' ? 'default' : 'secondary'}>{member.role}</Badge>
          ) : (
            <div className="flex items-center gap-2">
              <label className="sr-only" htmlFor={`role-${member.userId}`}>
                Role for {member.email}
              </label>
              <select
                id={`role-${member.userId}`}
                className="h-8 rounded-md border border-input bg-transparent px-2 text-sm"
                value={member.role}
                disabled={busy}
                onChange={(e) =>
                  changeRole.mutate({ userId: member.userId, role: e.target.value as ProjectRole })
                }
              >
                <option value="MEMBER">MEMBER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => remove.mutate(member.userId)}
              >
                Remove
              </Button>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
