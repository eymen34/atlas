import { useEffect } from 'react';
import { useSearchParams } from 'react-router';
import { BoardView } from '@/features/board/BoardView';
import { useProjectOutlet } from './context';

/**
 * Kanban board (T-027). The project is resolved by the parent ProjectDetailPage and
 * shared via outlet context — this page reads it, strips any stale ?status/?page from
 * the URL (status is the COLUMN axis, not a filter — E2 + ADDENDUM #1), and renders the
 * inner board. NOTE: the route points here (web/src/pages/project/BoardPage.tsx) — the
 * board pieces live in @/features/board.
 */
export function BoardPage() {
  const { project } = useProjectOutlet();
  const [searchParams, setSearchParams] = useSearchParams();

  useEffect(() => {
    if (searchParams.has('status') || searchParams.has('page')) {
      const next = new URLSearchParams(searchParams);
      next.delete('status');
      next.delete('page');
      setSearchParams(next, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  return <BoardView project={project} />;
}
