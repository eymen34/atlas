import {
  type ActivityEventResponse,
  ActivityService,
  type CommentResponse,
  CommentsService,
  type CreateTicketRequest,
  type LabelResponse,
  LabelsService,
  type PagedResponseTicketResponse,
  type TicketResponse,
  TicketsService,
  type TransitionRequest,
  type UpdateTicketRequest,
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
  // T-021 detail view. Keyed by the ROUTE identifier (idOrKey, e.g. ENG-42) since
  // that is what is available at mount; the HTTP calls below use the resolved UUID.
  detail: (idOrKey: string) => [...ticketKeys.all, 'detail', idOrKey] as const,
  activity: (idOrKey: string) => [...ticketKeys.all, 'detail', idOrKey, 'activity'] as const,
  // T-023 watchers. Keyed by ticket UUID (the watcher endpoints bind @PathVariable UUID).
  watchers: (ticketId: string) => [...ticketKeys.all, ticketId, 'watchers'] as const,
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

/**
 * Board variant of {@link listTickets} (T-027): status is the COLUMN axis on the
 * Kanban board, NOT a filter, so it is defensively stripped here (E2). The board
 * groups all of a project's tickets client-side from a single large page.
 */
export async function boardListTickets(
  projectId: string,
  filters: TicketFilters
): Promise<TicketPage> {
  return listTickets(projectId, { ...filters, status: undefined });
}

/** T-027 compile-time probe: TicketStatus stays the board's four-column axis. */
export const _boardStatusProbe: TicketStatus = 'TODO';

export async function listLabels(projectId: string): Promise<Label[]> {
  return (await LabelsService.listProjectLabels(projectId)).map(toLabel);
}

/* ───────────────────────── T-021: ticket detail ───────────────────────── */

/**
 * The 12 known activity event types (template-literal over the generated enum so
 * they track codegen). The wire may carry an unknown/future value, so
 * {@link ActivityEvent.eventType} is a raw string — consumers fall back safely.
 */
export type ActivityEventType = `${NonNullable<ActivityEventResponse['eventType']>}`;

export interface ActivityEvent {
  id: string;
  ticketId: string;
  /** Raw event type; one of {@link ActivityEventType} or an unknown/future value. */
  eventType: string;
  /** null = system / no actor; undefined = actor omitted; otherwise a user UUID. */
  actorId?: string | null;
  createdAt: string;
  /** Event-type-specific payload (e.g. {from,to} or {added,removed}). */
  payload: Record<string, unknown>;
}

/**
 * Normalizes a generated ActivityEventResponse. Every field is optional on the
 * wire; this never throws. NB the generated `payload` is typed as Jackson's
 * JsonNode reflection shape (a springdoc quirk) but at runtime is the real JSON
 * object, hence the unknown-bridge cast.
 */
export function toActivityEvent(raw: ActivityEventResponse): ActivityEvent {
  const payload =
    raw.payload && typeof raw.payload === 'object'
      ? (raw.payload as unknown as Record<string, unknown>)
      : {};
  return {
    id: raw.id ?? '',
    ticketId: raw.ticketId ?? '',
    eventType: raw.eventType ? String(raw.eventType) : 'UNKNOWN',
    actorId: raw.actorId,
    createdAt: raw.createdAt ?? '',
    payload,
  };
}

/**
 * Patch for {@link updateTicket}. `assigneeId: null` expresses an explicit
 * unassign — the generated UpdateTicketRequest types it as `string` only, so the
 * call casts. (Backend null-vs-absent semantics tracked as a backlog item.)
 */
export interface TicketPatch {
  title?: string;
  description?: string;
  priority?: TicketPriority;
  assigneeId?: string | null;
}

/** Fetch a single ticket by UUID id or display key (e.g. ENG-42). */
export async function getTicket(idOrKey: string): Promise<Ticket> {
  return toTicket(await TicketsService.getTicket(idOrKey));
}

/**
 * PATCH a ticket's fields (title/description/priority/assignee). Status is NOT
 * changed here — use {@link transitionTicket}. Takes the ticket UUID (the PATCH
 * endpoint binds @PathVariable UUID, not the display key).
 */
export async function updateTicket(ticketId: string, patch: TicketPatch): Promise<Ticket> {
  return toTicket(await TicketsService.updateTicket(ticketId, patch as UpdateTicketRequest));
}

/**
 * Transition a ticket's status (POST /transition — publishes an event + writes an
 * activity row; NOT a PATCH). Takes the ticket UUID.
 */
export async function transitionTicket(ticketId: string, toStatus: TicketStatus): Promise<Ticket> {
  return toTicket(
    await TicketsService.transitionTicket(ticketId, { toStatus } as TransitionRequest)
  );
}

/** Replace a ticket's full label set (PUT, idempotent). Takes the ticket UUID. */
export async function setTicketLabels(ticketId: string, labelIds: string[]): Promise<Ticket> {
  return toTicket(await TicketsService.setTicketLabels(ticketId, { labelIds }));
}

/** List a ticket's activity (newest first). Takes the ticket UUID; pinned page/size. */
export async function listTicketActivity(
  ticketId: string,
  page = 0,
  size = 20
): Promise<ActivityEvent[]> {
  const res = await ActivityService.listTicketActivity(ticketId, page, size);
  return (res.items ?? []).map(toActivityEvent);
}

/* ───────────────────────── T-022: comments ───────────────────────── */

export interface Comment {
  id: string;
  ticketId: string;
  authorId: string;
  /** null when the comment is server-redacted (deleted); D5. */
  body: string | null;
  deleted: boolean;
  /** SERVER-resolved mentioned member ids (D4); client metadata is never trusted. */
  mentionedUserIds: string[];
  createdAt: string;
  updatedAt: string;
}

export interface CommentPage {
  items: Comment[];
  page: number;
  size: number;
  total: number;
}

/** Maps a generated CommentResponse; throws on a malformed row (toTicket precedent). */
export function toComment(raw: CommentResponse): Comment {
  if (!raw.id || !raw.createdAt) {
    throw new Error('Malformed comment response: missing id or createdAt');
  }
  return {
    id: raw.id,
    ticketId: raw.ticketId ?? '',
    authorId: raw.authorId ?? '',
    body: raw.deleted ? null : (raw.body ?? ''),
    deleted: raw.deleted ?? false,
    mentionedUserIds: raw.mentionedUserIds ?? [],
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt ?? raw.createdAt,
  };
}

/** TanStack Query key for a ticket's comment page (page/size encoded). */
export const commentKeys = {
  list: (ticketId: string, page: number, size: number) =>
    ['tickets', ticketId, 'comments', page, size] as const,
};

export async function listComments(ticketId: string, page = 0, size = 20): Promise<CommentPage> {
  const res = await CommentsService.listComments(ticketId, page, size);
  return {
    items: (res.items ?? []).map(toComment),
    page: res.page ?? page,
    size: res.size ?? size,
    total: res.total ?? 0,
  };
}

export async function createComment(ticketId: string, body: string): Promise<Comment> {
  return toComment(await CommentsService.createComment(ticketId, { body }));
}

export async function updateComment(commentId: string, body: string): Promise<Comment> {
  return toComment(await CommentsService.updateComment(commentId, { body }));
}

export async function deleteComment(commentId: string): Promise<void> {
  await CommentsService.deleteComment(commentId);
}

/* ───────────────────────── T-023: watchers ───────────────────────── */

/** Watcher user ids for a ticket (bare array; small bounded set). */
export async function listWatchers(ticketId: string): Promise<string[]> {
  return (await TicketsService.listTicketWatchers(ticketId)) ?? [];
}

/** Idempotent watch (204; a second call is a no-op). */
export async function watchTicket(ticketId: string): Promise<void> {
  await TicketsService.watchTicket(ticketId);
}

/** Idempotent unwatch (204; a second call is a no-op). */
export async function unwatchTicket(ticketId: string): Promise<void> {
  await TicketsService.unwatchTicket(ticketId);
}
