import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router';
import { toast } from 'sonner';
import { ApiError } from '@/api/generated';
import { createProject, projectKeys } from '@/api/projects';
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
import { Textarea } from '@/components/ui/textarea';
import { suggestKey } from '@/lib/projectKey';
import { createProjectSchema, type CreateProjectInput } from '@/lib/projectSchemas';

export function NewProjectDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Once the user edits the key, stop auto-deriving it from the name.
  const [keyDirty, setKeyDirty] = useState(false);

  const {
    register,
    handleSubmit,
    setValue,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<CreateProjectInput>({
    resolver: zodResolver(createProjectSchema),
    defaultValues: { name: '', key: '', description: '' },
  });

  const mutation = useMutation({
    mutationFn: (values: CreateProjectInput) =>
      createProject({
        key: values.key,
        name: values.name,
        // Omit empty descriptions so the backend stores null, not ''.
        description: values.description?.trim() ? values.description : undefined,
      }),
    onSuccess: async (project) => {
      await queryClient.invalidateQueries({ queryKey: projectKeys.list });
      handleClose(false);
      navigate(`/projects/${project.key}`);
    },
    onError: (err) => {
      const status = err instanceof ApiError ? err.status : 0;
      if (status === 409) {
        setError('key', { message: 'Key already in use' });
      } else if (status === 400) {
        setError('root', { message: 'Please check the fields and try again.' });
      } else {
        toast.error('Could not create the project. Please try again.');
      }
    },
  });

  function handleClose(next: boolean) {
    if (!next) {
      reset();
      setKeyDirty(false);
    }
    onOpenChange(next);
  }

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  const nameField = register('name', {
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => {
      if (!keyDirty) {
        setValue('key', suggestKey(e.target.value), { shouldValidate: false });
      }
    },
  });
  const keyField = register('key', {
    onChange: () => {
      setKeyDirty(true);
    },
  });

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New project</DialogTitle>
          <DialogDescription>Create a project and become its first admin.</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} noValidate className="space-y-4">
          <div className="space-y-1">
            <Label htmlFor="project-name">Name</Label>
            <Input id="project-name" aria-invalid={errors.name ? 'true' : undefined} {...nameField} />
            {errors.name && (
              <p role="alert" className="text-sm text-destructive">
                {errors.name.message}
              </p>
            )}
          </div>
          <div className="space-y-1">
            <Label htmlFor="project-key">Key</Label>
            <Input id="project-key" aria-invalid={errors.key ? 'true' : undefined} {...keyField} />
            {errors.key && (
              <p role="alert" className="text-sm text-destructive">
                {errors.key.message}
              </p>
            )}
          </div>
          <div className="space-y-1">
            <Label htmlFor="project-description">Description</Label>
            <Textarea
              id="project-description"
              aria-invalid={errors.description ? 'true' : undefined}
              {...register('description')}
            />
            {errors.description && (
              <p role="alert" className="text-sm text-destructive">
                {errors.description.message}
              </p>
            )}
          </div>
          {errors.root && (
            <p role="alert" data-testid="form-error" className="text-sm text-destructive">
              {errors.root.message}
            </p>
          )}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => handleClose(false)}
              disabled={isSubmitting || mutation.isPending}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting || mutation.isPending}>
              Create project
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
