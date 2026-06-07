import { NavLink } from 'react-router';
import type { Project } from '@/api/projects';
import { Separator } from '@/components/ui/separator';
import { cn } from '@/lib/utils';

function itemClass({ isActive }: { isActive: boolean }): string {
  return cn(
    'block rounded-md px-3 py-2 text-sm transition-colors',
    isActive ? 'bg-accent font-medium text-accent-foreground' : 'text-muted-foreground hover:bg-accent/50'
  );
}

/**
 * Project shell navigation. Board + List are always present (stubs); Members +
 * Settings are rendered ONLY for ADMIN callers (conditional, NOT disabled).
 * This is UX gating — the backend still enforces ADMIN on every mutation.
 */
export function ProjectShellSidebar({ project }: { project: Project }) {
  const isAdmin = project.callerRole === 'ADMIN';
  return (
    <nav aria-label="Project sections" className="w-48 shrink-0 space-y-1">
      <NavLink to="board" className={itemClass}>
        Board
      </NavLink>
      <NavLink to="list" className={itemClass}>
        List
      </NavLink>
      {isAdmin && (
        <>
          <Separator className="my-2" />
          <NavLink to="members" className={itemClass}>
            Members
          </NavLink>
          <NavLink to="settings" className={itemClass}>
            Settings
          </NavLink>
        </>
      )}
    </nav>
  );
}
