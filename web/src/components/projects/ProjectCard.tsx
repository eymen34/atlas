import { Link } from 'react-router';
import type { Project } from '@/api/projects';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

/** Presentational, navigable project card. Navigation uses the human key. */
export function ProjectCard({ project }: { project: Project }) {
  return (
    <Link
      to={`/projects/${project.key}`}
      data-testid="project-card"
      className="block rounded-xl transition-colors hover:bg-accent/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <Card className="h-full">
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <Badge variant="secondary">{project.key}</Badge>
            <span className="text-xs text-muted-foreground">
              {project.memberCount} {project.memberCount === 1 ? 'member' : 'members'}
            </span>
          </div>
          <CardTitle className="mt-2">{project.name}</CardTitle>
        </CardHeader>
        {project.description ? (
          <CardContent>
            <p className="line-clamp-2 text-sm text-muted-foreground">{project.description}</p>
          </CardContent>
        ) : null}
      </Card>
    </Link>
  );
}
