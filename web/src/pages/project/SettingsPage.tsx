import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { useParams } from 'react-router';
import { toast } from 'sonner';
import { apiErrorMessage, listMembers, projectKeys, updateProject } from '@/api/projects';
import { AddMemberForm } from '@/components/projects/AddMemberForm';
import { MemberList } from '@/components/projects/MemberList';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';
import { Textarea } from '@/components/ui/textarea';
import { updateProjectSchema, type UpdateProjectInput } from '@/lib/projectSchemas';
import { useProjectOutlet } from './context';

export function SettingsPage() {
  const { project } = useProjectOutlet();
  const { projectIdOrKey } = useParams<{ projectIdOrKey: string }>();
  const idOrKey = projectIdOrKey ?? project.key;
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<UpdateProjectInput>({
    resolver: zodResolver(updateProjectSchema),
    defaultValues: { name: project.name, description: project.description ?? '' },
  });

  const update = useMutation({
    mutationFn: (values: UpdateProjectInput) =>
      updateProject(project.id, { name: values.name, description: values.description ?? '' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: projectKeys.detail(idOrKey) });
      void queryClient.invalidateQueries({ queryKey: projectKeys.list });
      toast.success('Project updated');
    },
    onError: (err) => toast.error(apiErrorMessage(err, 'Could not update the project.')),
  });

  const membersQuery = useQuery({
    queryKey: projectKeys.members(project.id),
    queryFn: () => listMembers(project.id),
  });

  const onSubmit = handleSubmit((values) => update.mutate(values));

  return (
    <div className="space-y-10">
      <section className="space-y-4">
        <h2 className="text-lg font-semibold">Details</h2>
        <form onSubmit={onSubmit} noValidate className="max-w-md space-y-4">
          <div className="space-y-1">
            <Label htmlFor="settings-name">Name</Label>
            <Input
              id="settings-name"
              aria-invalid={errors.name ? 'true' : undefined}
              {...register('name')}
            />
            {errors.name && (
              <p role="alert" className="text-sm text-destructive">
                {errors.name.message}
              </p>
            )}
          </div>
          <div className="space-y-1">
            <Label htmlFor="settings-description">Description</Label>
            <Textarea
              id="settings-description"
              aria-invalid={errors.description ? 'true' : undefined}
              {...register('description')}
            />
            {errors.description && (
              <p role="alert" className="text-sm text-destructive">
                {errors.description.message}
              </p>
            )}
          </div>
          <Button type="submit" disabled={isSubmitting || update.isPending}>
            Save changes
          </Button>
        </form>
      </section>

      <Separator />

      <section className="space-y-4">
        <h2 className="text-lg font-semibold">Members</h2>
        <AddMemberForm projectId={project.id} idOrKey={idOrKey} />
        {membersQuery.isLoading && (
          <p className="text-sm text-muted-foreground">Loading members…</p>
        )}
        {membersQuery.isError && (
          <p role="alert" className="text-sm text-destructive">
            Could not load members.
          </p>
        )}
        {membersQuery.data && (
          <MemberList
            projectId={project.id}
            idOrKey={idOrKey}
            members={membersQuery.data}
            readOnly={false}
          />
        )}
      </section>
    </div>
  );
}
