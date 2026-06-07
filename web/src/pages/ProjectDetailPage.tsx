import { useQuery } from '@tanstack/react-query';
import { Outlet, useParams } from 'react-router';
import { getProject, projectKeys } from '@/api/projects';
import { ProjectShellSidebar } from '@/components/projects/ProjectShellSidebar';
import { Badge } from '@/components/ui/badge';
import type { ProjectOutletContext } from './project/context';

export function ProjectDetailPage() {
  const { projectIdOrKey } = useParams<{ projectIdOrKey: string }>();
  const {
    data: project,
    isLoading,
    isError,
  } = useQuery({
    queryKey: projectKeys.detail(projectIdOrKey ?? ''),
    queryFn: () => getProject(projectIdOrKey ?? ''),
    enabled: Boolean(projectIdOrKey),
  });

  if (isLoading) {
    return <div data-testid="project-loading" className="h-24 animate-pulse rounded-xl bg-muted/40" />;
  }

  if (isError || !project) {
    return (
      <div data-testid="project-not-found" className="rounded-xl border border-dashed py-16 text-center">
        <p className="text-sm text-muted-foreground">Project not found.</p>
      </div>
    );
  }

  return (
    <div>
      <header className="flex items-center gap-3">
        <Badge variant="secondary">{project.key}</Badge>
        <h1 className="text-2xl font-semibold tracking-tight">{project.name}</h1>
      </header>
      <div className="mt-6 flex gap-8">
        <ProjectShellSidebar project={project} />
        <div className="min-w-0 flex-1">
          <Outlet context={{ project } satisfies ProjectOutletContext} />
        </div>
      </div>
    </div>
  );
}
