import { useQuery } from '@tanstack/react-query';
import { SearchIcon } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { searchAllTickets, searchKeys } from '@/api/search';
import { Button } from '@/components/ui/button';
import {
  Command,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useDebouncedValue } from '@/lib/useDebouncedValue';
import { SearchResultRow } from './SearchResultRow';

const DEBOUNCE_MS = 250;

/**
 * Global ⌘K / Ctrl+K full-text search (T-028). Self-contained: it owns the open state,
 * registers the keyboard shortcut, and renders the header trigger button + the command
 * dialog. Results are a FLAT ranked list across all the caller's projects (authz is
 * enforced server-side in SQL). cmdk's client filtering is disabled ({@code shouldFilter=false})
 * so server-ranked, stemmed results are shown verbatim rather than re-filtered by substring.
 */
export function GlobalSearchDialog() {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const debounced = useDebouncedValue(q, DEBOUNCE_MS);
  const navigate = useNavigate();

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);

  const trimmed = debounced.trim();
  const query = useQuery({
    queryKey: searchKeys.global(trimmed),
    queryFn: ({ signal }) => searchAllTickets(trimmed, 0, 20, signal),
    enabled: open && trimmed.length > 0,
  });
  const hits = query.data?.items ?? [];

  function handleOpenChange(next: boolean) {
    setOpen(next);
    if (!next) {
      setQ('');
    }
  }

  function go(projectKey: string, ticketKey: string) {
    handleOpenChange(false);
    navigate(`/projects/${projectKey}/tickets/${ticketKey}`);
  }

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        type="button"
        data-testid="global-search-trigger"
        aria-label="Search tickets"
        className="gap-2 text-muted-foreground"
        onClick={() => setOpen(true)}
      >
        <SearchIcon className="h-4 w-4" aria-hidden="true" />
        <span className="hidden sm:inline">Search</span>
        <kbd className="hidden rounded border bg-muted px-1.5 text-[10px] font-medium sm:inline">
          ⌘K
        </kbd>
      </Button>

      <Dialog open={open} onOpenChange={handleOpenChange}>
        <DialogContent className="overflow-hidden p-0" showCloseButton={false}>
          <DialogHeader className="sr-only">
            <DialogTitle>Search tickets</DialogTitle>
            <DialogDescription>
              Full-text search across all of your projects&apos; tickets.
            </DialogDescription>
          </DialogHeader>
          <Command shouldFilter={false}>
            <CommandInput
              value={q}
              onValueChange={setQ}
              placeholder="Search tickets…"
              data-testid="global-search-input"
            />
            <CommandList>
              {trimmed.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">
                  Type to search your tickets…
                </p>
              ) : query.isLoading ? (
                <p className="py-6 text-center text-sm text-muted-foreground">Searching…</p>
              ) : query.isError ? (
                <p className="py-6 text-center text-sm text-muted-foreground">Search failed.</p>
              ) : hits.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">No tickets found.</p>
              ) : (
                hits.map((hit) => (
                  <CommandItem
                    key={hit.ticketId}
                    value={hit.ticketId}
                    data-testid="global-search-result"
                    onSelect={() => go(hit.projectKey, hit.ticketKey)}
                  >
                    <SearchResultRow hit={hit} />
                  </CommandItem>
                ))
              )}
            </CommandList>
          </Command>
        </DialogContent>
      </Dialog>
    </>
  );
}
