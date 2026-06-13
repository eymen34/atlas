import { NavLink, useLocation, useParams } from 'react-router';
import { cn } from '@/lib/utils';

/**
 * Board ⇄ List toggle (T-027, D5). Preserves the query string across the switch by
 * carrying {@code location.search} into each target, so filters survive a Board↔List
 * round-trip (AC4). The links use absolute paths built from the route's
 * {@code projectIdOrKey} param (the display key the URL already carries).
 */
export function ProjectViewToggle() {
  const { projectIdOrKey = '' } = useParams();
  const { search } = useLocation();
  const base = `/projects/${projectIdOrKey}`;

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    cn(
      'rounded px-3 py-1 text-sm transition-colors',
      isActive
        ? 'bg-background font-medium text-foreground shadow-sm'
        : 'text-muted-foreground hover:text-foreground'
    );

  return (
    <div
      role="tablist"
      aria-label="View"
      data-testid="project-view-toggle"
      className="inline-flex gap-1 rounded-md border border-border bg-muted/40 p-0.5"
    >
      <NavLink to={{ pathname: `${base}/board`, search }} className={linkClass} end>
        Board
      </NavLink>
      <NavLink to={{ pathname: `${base}/list`, search }} className={linkClass} end>
        List
      </NavLink>
    </div>
  );
}
