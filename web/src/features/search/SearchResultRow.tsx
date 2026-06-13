import type { SearchHit } from '@/api/search';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { SnippetText } from './SnippetText';

/** Status chip colors — mirrors the ticket list's STATUS_CLASS so search rows read the same. */
const STATUS_CLASS: Record<string, string> = {
  TODO: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
  IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-200',
  IN_REVIEW: 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-200',
  DONE: 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-200',
};

/** One flat ranked search hit: key + title + status, with the highlighted snippet beneath. */
export function SearchResultRow({ hit }: { hit: SearchHit }) {
  return (
    <div className="flex w-full flex-col gap-1 overflow-hidden">
      <div className="flex items-center gap-2">
        <span className="font-mono text-xs text-muted-foreground">{hit.ticketKey}</span>
        <span className="truncate text-sm font-medium text-foreground">{hit.title}</span>
        <Badge className={cn('ml-auto shrink-0 border-transparent', STATUS_CLASS[hit.status])}>
          {hit.status}
        </Badge>
      </div>
      {hit.snippet && <SnippetText snippet={hit.snippet} />}
    </div>
  );
}
