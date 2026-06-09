import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { SIZE_OPTIONS } from './useTicketFilters';

export interface PagerFooterProps {
  /** Zero-based page index (matches the Spring Pageable contract used by the backend). */
  page: number;
  size: number;
  total: number;
  onChange: (page: number, size: number) => void;
}

/**
 * Footer pager for the ticket list. Pages are ZERO-based: Prev is disabled on
 * page 0, Next is disabled once {@code (page + 1) * size >= total}. Changing the
 * page size resets to page 0.
 */
export function PagerFooter({ page, size, total, onChange }: PagerFooterProps) {
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, total);
  const isFirst = page <= 0;
  const isLast = (page + 1) * size >= total;

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 pt-2 text-sm">
      <p className="text-muted-foreground" data-testid="pager-summary">
        {total === 0 ? 'No tickets' : `Showing ${from}–${to} of ${total}`}
      </p>
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground">Rows</span>
        <Select value={String(size)} onValueChange={(v) => onChange(0, Number(v))}>
          <SelectTrigger size="sm" className="w-[72px]" aria-label="Rows per page">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SIZE_OPTIONS.map((s) => (
              <SelectItem key={s} value={String(s)}>
                {s}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onChange(page - 1, size)}
          disabled={isFirst}
        >
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onChange(page + 1, size)}
          disabled={isLast}
        >
          Next
        </Button>
      </div>
    </div>
  );
}
