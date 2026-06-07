import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router';
import { listMembers, projectKeys } from '@/api/projects';
import { MemberList } from '@/components/projects/MemberList';
import { useProjectOutlet } from './context';

export function MembersPage() {
  const { project } = useProjectOutlet();
  const { projectIdOrKey } = useParams<{ projectIdOrKey: string }>();
  const idOrKey = projectIdOrKey ?? project.key;

  const { data, isLoading, isError } = useQuery({
    queryKey: projectKeys.members(project.id),
    queryFn: () => listMembers(project.id),
  });

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">Members</h2>
      {isLoading && <p className="text-sm text-muted-foreground">Loading members…</p>}
      {isError && (
        <p role="alert" className="text-sm text-destructive">
          Could not load members.
        </p>
      )}
      {data && (
        <MemberList projectId={project.id} idOrKey={idOrKey} members={data} readOnly />
      )}
    </section>
  );
}
