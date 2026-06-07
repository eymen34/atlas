import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { listProjects, projectKeys } from '@/api/projects';
import { NewProjectDialog } from '@/components/projects/NewProjectDialog';
import { ProjectCard } from '@/components/projects/ProjectCard';
import { Button } from '@/components/ui/button';

export function ProjectsPage() {
  const [dialogOpen, setDialogOpen] = useState(false);
  const { data, isLoading, isError } = useQuery({
    queryKey: projectKeys.list,
    queryFn: listProjects,
  });

  return (
    <div>
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Projects</h1>
        <Button type="button" onClick={() => setDialogOpen(true)}>
          New project
        </Button>
      </div>

      {isLoading && (
        <div
          data-testid="projects-loading"
          className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
        >
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-32 animate-pulse rounded-xl border bg-muted/40" />
          ))}
        </div>
      )}

      {isError && (
        <p role="alert" className="mt-6 text-sm text-destructive">
          Could not load your projects. Please refresh and try again.
        </p>
      )}

      {!isLoading && !isError && data && data.length === 0 && (
        <div
          data-testid="projects-empty"
          className="mt-10 flex flex-col items-center gap-3 rounded-xl border border-dashed py-16 text-center"
        >
          <p className="text-sm text-muted-foreground">No projects yet — create your first one.</p>
          <Button type="button" onClick={() => setDialogOpen(true)}>
            Create your first project
          </Button>
        </div>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {data.map((project) => (
            <ProjectCard key={project.id} project={project} />
          ))}
        </div>
      )}

      <NewProjectDialog open={dialogOpen} onOpenChange={setDialogOpen} />
    </div>
  );
}
