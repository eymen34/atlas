import {
  type CreateTicketRequest,
  type LabelResponse,
  LabelsService,
  type PagedResponseTicketResponse,
  type TicketResponse,
  TicketsService,
} from './generated';

/**
 * T-020 app-facing ticket/label read+create API, mirroring projects.ts.
 *
 * The generated client models every field as optional and emits status/priority
 * as namespaced string enums on TicketResponse but as inline literal unions on the
 * listProjectTickets query params. We derive the app-facing {@link TicketStatus} /
 * {@link TicketPriority} as the LITERAL union (via a template-literal over the
 * generated enum) so they stay in sync with codegen AND are directly assignable to
 * the query-param types. Components never touch the generated services directly —
 * they go through these wrappers.
 */
export type TicketStatus = `${NonNullable<TicketResponse['status']>}`;
export type TicketPriority = `${NonNullable<TicketResponse['priority']>}`;

/** Canonical option order for the filter controls (the backend CHECK set). */
export const STATUS_OPTIONS: readonly TicketStatus[] = ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'];
export const PRIORITY_OPTIONS: readonly TicketPriority[] = ['P0', 'P1', 'P2', 'P3'];

export interface Ticket {
  id: string;
  key: string;
  title: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
  assigneeId?: string;
  reporterId: string;
  labelIds: string[];
  createdAt: string;
  updatedAt: string;
  projectId: string;
  number: number;
}

export interface Label {
  id: string;
  name: string;
  color?: string;
  projectId: string;
  createdAt: string;
}

export interface TicketFilters {
  status?: TicketStatus[];
  priority?: TicketPriority[];
  /** A user UUID, or the literal 'unassigned' sentinel (forwarded to the backend). */
  assigneeId?: string;
  label?: string[];
  page: number;
  size: number;
}

export interface TicketPage {
  items: Ticket[];
  page: number;
  size: number;
  total: number;
}

/** Maps a generated TicketResponse to the app shape; throws on a malformed row. */
export function toTicket(r: TicketResponse): Ticket {
  if (!r.id || !r.key || !r.title) {
    throw new Error('Malformed ticket response: missing id, key, or title');
  }
  return {
    id: r.id,
    key: r.key,
    title: r.title,
    description: r.description,
    // status/priority arrive as string enums; coerce to the literal-union app type.
    status: (r.status ? String(r.status) : 'TODO') as TicketStatus,
    priority: (r.priority ? String(r.priority) : 'P2') as TicketPriority,
    assigneeId: r.assigneeId,
    reporterId: r.reporterId ?? '',
    labelIds: r.labelIds ?? [],
    createdAt: r.createdAt ?? '',
    updatedAt: r.updatedAt ?? '',
    projectId: r.projectId ?? '',
    number: r.number ?? 0,
  };
}

function toLabel(r: LabelResponse): Label {
  return {
    id: r.id ?? '',
    name: r.name ?? '',
    color: r.color,
    projectId: r.projectId ?? '',
    createdAt: r.createdAt ?? '',
  };
}

/**
 * TanStack Query keys. {@link ticketKeys.list} encodes EVERY filter param so
 * distinct filter/page combos cache independently and identical combos share a
 * cache entry; {@link ticketKeys.lists} is the invalidation prefix for all lists
 * of a project (used after create — D1).
 */
export const ticketKeys = {
  all: ['tickets'] as const,
  lists: (projectId: string) => [...ticketKeys.all, projectId, 'list'] as const,
  list: (projectId: string, filters: TicketFilters) =>
    [
      ...ticketKeys.lists(projectId),
      {
        status: filters.status ?? [],
        priority: filters.priority ?? [],
        assigneeId: filters.assigneeId ?? null,
        label: filters.label ?? [],
        page: filters.page,
        size: filters.size,
      },
    ] as const,
  labels: (projectId: string) => [...ticketKeys.all, projectId, 'labels'] as const,
};

export async function listTickets(projectId: string, filters: TicketFilters): Promise<TicketPage> {
  const res: PagedResponseTicketResponse = await TicketsService.listProjectTickets(
    projectId,
    filters.status && filters.status.length > 0 ? filters.status : undefined,
    filters.priority && filters.priority.length > 0 ? filters.priority : undefined,
    filters.assigneeId,
    filters.label && filters.label.length > 0 ? filters.label : undefined,
    undefined, // q — search not wired in T-020
    filters.page,
    filters.size
  );
  return {
    items: (res.items ?? []).map(toTicket),
    page: res.page ?? filters.page,
    size: res.size ?? filters.size,
    total: res.total ?? 0,
  };
}

export async function createTicket(projectId: string, req: CreateTicketRequest): Promise<Ticket> {
  return toTicket(await TicketsService.createTicket(projectId, req));
}

export async function listLabels(projectId: string): Promise<Label[]> {
  return (await LabelsService.listProjectLabels(projectId)).map(toLabel);
}
