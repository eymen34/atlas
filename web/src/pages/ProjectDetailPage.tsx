import { useParams } from 'react-router';

export function ProjectDetailPage() {
  const { projectId } = useParams<{ projectId: string }>();
  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Project: {projectId}</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        The project detail view lands in a later ticket.
      </p>
    </div>
  );
}
