import {
  type CancelablePromise,
  type PagedResponseTicketSearchResult,
  SearchService,
  type TicketSearchResult,
} from './generated';

/**
 * T-028 app-facing full-text search API, mirroring tickets.ts / notifications.ts.
 *
 * Two endpoints: a project-scoped search (any member) and a global search across the
 * caller's projects. The generated client models every field as optional and emits
 * {@code status} as a namespaced string enum; we derive the app-facing {@link SearchStatus}
 * as the LITERAL union so it tracks codegen. Components/hooks go through these wrappers,
 * never the generated service.
 *
 * The {@code snippet} carries {@code [[ ]]} highlight sentinels from ts_headline; render it
 * ONLY through {@code SnippetText} (never dangerouslySetInnerHTML).
 */
export type SearchStatus = `${NonNullable<TicketSearchResult['status']>}`;

export interface SearchHit {
  ticketId: string;
  /** Display key, e.g. "ENG-42" — combined with {@link projectKey} for the row link. */
  ticketKey: string;
  title: string;
  status: SearchStatus;
  projectKey: string;
  projectId: string;
  /** ts_headline output with {@code [[ ]]} highlight sentinels. */
  snippet: string;
  updatedAt: string;
  /** ts_rank_cd relevance (higher = more relevant). */
  rank: number;
}

export interface SearchPage {
  items: SearchHit[];
  page: number;
  size: number;
  total: number;
}

/** Maps a generated TicketSearchResult to the app shape; null-safe (never throws). */
export function toSearchHit(r: TicketSearchResult): SearchHit {
  return {
    ticketId: r.ticketId ?? '',
    ticketKey: r.ticketKey ?? '',
    title: r.title ?? '',
    status: (r.status ? String(r.status) : 'TODO') as SearchStatus,
    projectKey: r.projectKey ?? '',
    projectId: r.projectId ?? '',
    snippet: r.snippet ?? '',
    updatedAt: r.updatedAt ?? '',
    rank: r.rank ?? 0,
  };
}

/**
 * TanStack Query keys. Global and project searches cache under distinct prefixes,
 * each keyed by the (trimmed) query string so identical queries share a cache entry.
 */
export const searchKeys = {
  all: ['search'] as const,
  global: (q: string) => [...searchKeys.all, 'global', q] as const,
  project: (projectId: string, q: string) =>
    [...searchKeys.all, 'project', projectId, q] as const,
};

function toPage(res: PagedResponseTicketSearchResult, page: number, size: number): SearchPage {
  return {
    items: (res.items ?? []).map(toSearchHit),
    page: res.page ?? page,
    size: res.size ?? size,
    total: res.total ?? 0,
  };
}

/**
 * Bridges TanStack Query's AbortSignal to the generated client's CancelablePromise so an
 * outdated/superseded search (the user kept typing) actually aborts the in-flight request
 * instead of resolving and racing. A cancelled promise rejects with CancelError, which the
 * query layer discards.
 */
function abortable<T>(promise: CancelablePromise<T>, signal?: AbortSignal): CancelablePromise<T> {
  if (signal) {
    if (signal.aborted) {
      promise.cancel();
    } else {
      signal.addEventListener('abort', () => promise.cancel(), { once: true });
    }
  }
  return promise;
}

export async function searchAllTickets(
  q: string,
  page = 0,
  size = 25,
  signal?: AbortSignal
): Promise<SearchPage> {
  const res = await abortable(SearchService.searchAllTickets(q, page, size), signal);
  return toPage(res, page, size);
}

export async function searchProjectTickets(
  projectId: string,
  q: string,
  page = 0,
  size = 25,
  signal?: AbortSignal
): Promise<SearchPage> {
  const res = await abortable(
    SearchService.searchProjectTickets(projectId, q, page, size),
    signal
  );
  return toPage(res, page, size);
}
