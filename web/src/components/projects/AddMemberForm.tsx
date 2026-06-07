import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';
import {
  addMember,
  apiErrorMessage,
  apiErrorStatus,
  type ProjectRole,
  projectKeys,
} from '@/api/projects';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { addMemberSchema, type AddMemberInput } from '@/lib/projectSchemas';

/**
 * {@code projectId} is the project UUID (the add-member endpoint requires a
 * UUID); {@code idOrKey} is the URL segment used as the detail-query cache key.
 */
export function AddMemberForm({
  projectId,
  idOrKey,
}: {
  projectId: string;
  idOrKey: string;
}) {
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<AddMemberInput>({
    resolver: zodResolver(addMemberSchema),
    defaultValues: { email: '', role: 'MEMBER' },
  });

  const mutation = useMutation({
    mutationFn: (values: AddMemberInput) =>
      addMember(projectId, values.email, values.role as ProjectRole),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: projectKeys.members(projectId) });
      void queryClient.invalidateQueries({ queryKey: projectKeys.detail(idOrKey) });
      reset();
    },
    onError: (err) => {
      const status = apiErrorStatus(err);
      if (status === 404) {
        toast.error('No registered user with that email');
      } else if (status === 409) {
        toast.error('User is already a member');
      } else {
        toast.error(apiErrorMessage(err, 'Could not add the member.'));
      }
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-wrap items-end gap-3">
      <div className="space-y-1">
        <Label htmlFor="add-member-email">Email</Label>
        <Input
          id="add-member-email"
          type="email"
          className="w-64"
          aria-invalid={errors.email ? 'true' : undefined}
          {...register('email')}
        />
        {errors.email && (
          <p role="alert" className="text-sm text-destructive">
            {errors.email.message}
          </p>
        )}
      </div>
      <div className="space-y-1">
        <Label htmlFor="add-member-role">Role</Label>
        <select
          id="add-member-role"
          className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
          {...register('role')}
        >
          <option value="MEMBER">MEMBER</option>
          <option value="ADMIN">ADMIN</option>
        </select>
      </div>
      <Button type="submit" disabled={isSubmitting || mutation.isPending}>
        Add member
      </Button>
    </form>
  );
}
