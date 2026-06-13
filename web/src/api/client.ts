import {
  AttachmentsService,
  AuthService,
  CommentsService,
  ConfigService,
  LabelsService,
  LinksService,
  NotificationsService,
  ProjectMembersService,
  ProjectResponse,
  ProjectsService,
  SearchService,
  TicketsService,
} from './generated';
import { OpenAPI } from './generated/core/OpenAPI';
import { useAuthStore } from '../store/authStore';
import { nativeFetch } from './nativeFetch';
import { getRefreshPromise } from './refreshSingleton';

/**
 * T-013 API client. Extends the T-010 wrapper: configures the generated
 * openapi-typescript-codegen client, exposes the 401 seam, AND adds
 * fetchWithAuth (Bearer injection + silent single-flight refresh on 401).
 *
 * Step 0b record: the generated OpenAPI.BASE default is 'http://localhost:8080';
 * we force '' (same-origin) so the dev proxy / prod static serving work and so
 * codegen regeneration cannot silently change the interceptor's base.
 */
OpenAPI.BASE = '';
OpenAPI.TOKEN = async () => useAuthStore.getState().accessToken ?? '';

let onUnauthorizedHandler: () => void = () => {
  useAuthStore.getState().clearTokens();
};

export function setOnUnauthorized(handler: () => void): void {
  onUnauthorizedHandler = handler;
}

export function handleUnauthorized(): void {
  onUnauthorizedHandler();
}

// Auth endpoints never carry a Bearer and must never trigger the refresh loop.
const AUTH_ENDPOINTS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
];

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

function withBearer(init: RequestInit | undefined, token: string | null): RequestInit {
  const headers = new Headers(init?.headers);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  return { ...init, headers };
}

/**
 * Wraps a fetch call with Bearer injection and silent refresh. On 401 with a
 * refresh token present, awaits the singleton refresh and retries once; on no
 * token or refresh failure, invokes the onUnauthorized handler and returns the
 * original 401.
 */
export async function fetchWithAuth(
  input: RequestInfo | URL,
  init?: RequestInit
): Promise<Response> {
  if (AUTH_ENDPOINTS.some((path) => urlOf(input).includes(path))) {
    return nativeFetch(input, init);
  }

  const response = await nativeFetch(
    input,
    withBearer(init, useAuthStore.getState().accessToken)
  );
  if (response.status !== 401) {
    return response;
  }

  if (!useAuthStore.getState().refreshToken) {
    onUnauthorizedHandler();
    return response;
  }

  try {
    const bundle = await getRefreshPromise();
    return await nativeFetch(input, withBearer(init, bundle.accessToken));
  } catch {
    onUnauthorizedHandler();
    return response;
  }
}

// Integration: openapi-typescript-codegen 0.29.0 has no response-interceptor
// hook (OpenAPIConfig exposes no FETCH). Feature-detect FETCH; otherwise
// monkeypatch the global fetch ONCE (idempotent via a well-known Symbol) so
// generated-client calls flow through fetchWithAuth.
const PATCH_FLAG = Symbol.for('atlas.fetchPatched');
type PatchedGlobal = Record<symbol, boolean>;

if ('FETCH' in OpenAPI) {
  (OpenAPI as unknown as { FETCH: typeof fetch }).FETCH = fetchWithAuth;
} else if (
  typeof window !== 'undefined' &&
  !(window as unknown as PatchedGlobal)[PATCH_FLAG]
) {
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) =>
    fetchWithAuth(input, init);
  (window as unknown as PatchedGlobal)[PATCH_FLAG] = true;
}

export { AuthService, ProjectsService, ProjectMembersService, ProjectResponse };

// Compile-time probe: forces tsc to validate the generated get-current-user
// operation (operationId getMe) still resolves after codegen.
export const _getMeTypeProbe: typeof AuthService.getMe = AuthService.getMe;

// T-016 compile-time probes: fail `tsc -b` if codegen drifts from the project /
// member API the frontend depends on (method names, params, response shapes).
export const _projectListProbe: typeof ProjectsService.list = ProjectsService.list;
export const _projectCreateProbe: typeof ProjectsService.create = ProjectsService.create;
export const _projectGetProbe: typeof ProjectsService.get = ProjectsService.get;
export const _projectUpdateProbe: typeof ProjectsService.update = ProjectsService.update;
export const _memberAddProbe: typeof ProjectMembersService.add = ProjectMembersService.add;
export const _memberChangeRoleProbe: typeof ProjectMembersService.changeRole =
  ProjectMembersService.changeRole;

// Field-shape probe: callerRole is the ProjectRole enum and memberCount is
// numeric on ProjectResponse. Referenced (exported) so it is not flagged unused.
export const _projectResponseFieldProbe = (
  p: ProjectResponse
): [ProjectResponse.callerRole | undefined, number | undefined] => [p.callerRole, p.memberCount];

// T-020 compile-time probes: fail `tsc -b` if codegen drifts from the ticket /
// label API the list view depends on (operationId / method-name stability).
export const _ticketListProbe: typeof TicketsService.listProjectTickets =
  TicketsService.listProjectTickets;
export const _ticketCreateProbe: typeof TicketsService.createTicket = TicketsService.createTicket;
export const _labelListProbe: typeof LabelsService.listProjectLabels =
  LabelsService.listProjectLabels;

// T-022 compile-time probes: fail `tsc -b` if codegen drifts from the comment API.
export const _createCommentProbe: typeof CommentsService.createComment =
  CommentsService.createComment;
export const _listCommentsProbe: typeof CommentsService.listComments =
  CommentsService.listComments;
export const _updateCommentProbe: typeof CommentsService.updateComment =
  CommentsService.updateComment;
export const _deleteCommentProbe: typeof CommentsService.deleteComment =
  CommentsService.deleteComment;

// T-023 compile-time probes: fail `tsc -b` if codegen drifts from the watcher /
// public-config API the watch toggle depends on.
export const _publicConfigProbe: typeof ConfigService.getPublicConfig =
  ConfigService.getPublicConfig;
export const _watchTicketProbe: typeof TicketsService.watchTicket = TicketsService.watchTicket;
export const _unwatchTicketProbe: typeof TicketsService.unwatchTicket =
  TicketsService.unwatchTicket;
export const _listTicketWatchersProbe: typeof TicketsService.listTicketWatchers =
  TicketsService.listTicketWatchers;

// T-024 compile-time probes: fail `tsc -b` if codegen drifts from the notification
// API the bell depends on (operationId / method-name stability).
export const _listNotificationsProbe: typeof NotificationsService.listNotifications =
  NotificationsService.listNotifications;
export const _markNotificationReadProbe: typeof NotificationsService.markNotificationRead =
  NotificationsService.markNotificationRead;
export const _markAllNotificationsReadProbe: typeof NotificationsService.markAllNotificationsRead =
  NotificationsService.markAllNotificationsRead;

// T-025 compile-time probes: fail `tsc -b` if codegen drifts from the attachment API.
export const _initAttachmentUploadProbe: typeof AttachmentsService.initAttachmentUpload =
  AttachmentsService.initAttachmentUpload;
export const _finalizeAttachmentProbe: typeof AttachmentsService.finalizeAttachment =
  AttachmentsService.finalizeAttachment;
export const _listTicketAttachmentsProbe: typeof AttachmentsService.listTicketAttachments =
  AttachmentsService.listTicketAttachments;
export const _getAttachmentDownloadUrlProbe: typeof AttachmentsService.getAttachmentDownloadUrl =
  AttachmentsService.getAttachmentDownloadUrl;
export const _deleteAttachmentProbe: typeof AttachmentsService.deleteAttachment =
  AttachmentsService.deleteAttachment;

// T-026 compile-time probes: fail `tsc -b` if codegen drifts from the link API.
export const _createTicketLinkProbe: typeof LinksService.createTicketLink =
  LinksService.createTicketLink;
export const _listTicketLinksProbe: typeof LinksService.listTicketLinks =
  LinksService.listTicketLinks;
export const _deleteTicketLinkProbe: typeof LinksService.deleteTicketLink =
  LinksService.deleteTicketLink;

// T-028 compile-time probes: fail `tsc -b` if codegen drifts from the search API
// (operationId / method-name stability — exactly two new operations).
export const _searchProjectTicketsProbe: typeof SearchService.searchProjectTickets =
  SearchService.searchProjectTickets;
export const _searchAllTicketsProbe: typeof SearchService.searchAllTickets =
  SearchService.searchAllTickets;
