import { useQuery } from '@tanstack/react-query';
import { SearchIcon } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { searchKeys, searchProjectTickets } from '@/api/search';
import { Input } from '@/components/ui/input';
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover';
import { useDebouncedValue } from '@/lib/useDebouncedValue';
import { SearchResultRow } from './SearchResultRow';

const DEBOUNCE_MS = 250;

/**
 * Per-project full-text search (T-028): a debounced input with a results popover scoped to
 * one project. The popover is anchored to the input and declines auto-focus so the user keeps
 * typing while results stream in. Selecting a hit navigates to the ticket detail page.
 */
export function ProjectSearchInput({
  projectId,
  projectKey,
}: {
  projectId: string;
  projectKey: string;
}) {
  const [q, setQ] = useState('');
  const [focused, setFocused] = useState(false);
  const debounced = useDebouncedValue(q, DEBOUNCE_MS);
  const navigate = useNavigate();

  const trimmed = debounced.trim();
  const query = useQuery({
    queryKey: searchKeys.project(projectId, trimmed),
    queryFn: ({ signal }) => searchProjectTickets(projectId, trimmed, 0, 10, signal),
    enabled: trimmed.length > 0,
  });
  const hits = query.data?.items ?? [];
  const open = focused && trimmed.length > 0;

  function go(ticketKey: string) {
    setFocused(false);
    setQ('');
    navigate(`/projects/${projectKey}/tickets/${ticketKey}`);
  }

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setFocused(false);
        }
      }}
    >
      <PopoverAnchor asChild>
        <div className="relative w-64">
          <SearchIcon
            className="absolute top-1/2 left-2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onFocus={() => setFocused(true)}
            placeholder="Search this project…"
            aria-label="Search this project's tickets"
            data-testid="project-search-input"
            className="pl-8"
          />
        </div>
      </PopoverAnchor>
      <PopoverContent
        align="start"
        className="w-80 p-1"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        {query.isLoading ? (
          <p className="py-4 text-center text-sm text-muted-foreground">Searching…</p>
        ) : query.isError ? (
          <p className="py-4 text-center text-sm text-muted-foreground">Search failed.</p>
        ) : hits.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">No tickets found.</p>
        ) : (
          <ul>
            {hits.map((hit) => (
              <li key={hit.ticketId}>
                <button
                  type="button"
                  data-testid="project-search-result"
                  onClick={() => go(hit.ticketKey)}
                  className="w-full rounded-sm px-2 py-2 text-left hover:bg-accent"
                >
                  <SearchResultRow hit={hit} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  );
}
