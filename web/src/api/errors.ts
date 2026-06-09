import { ApiError } from './generated';

/**
 * Shared HTTP-error helpers (T-020 extraction). The generated request layer
 * throws {@link ApiError} (status + parsed body) on non-2xx; these normalize that
 * for the UI. Imported by every api wrapper (projects.ts, tickets.ts).
 */

/** HTTP status of a thrown generated {@link ApiError}, or 0 for non-API errors. */
export function apiErrorStatus(err: unknown): number {
  return err instanceof ApiError ? err.status : 0;
}

/**
 * The backend `{status,error,message,path}.message` if present, else {@code fallback}.
 * {@code fallback} is optional so callers that just want "best available message"
 * can omit it.
 */
export function apiErrorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (err instanceof ApiError) {
    const body = err.body as { message?: unknown } | null | undefined;
    if (body && typeof body.message === 'string' && body.message.trim()) {
      return body.message;
    }
  }
  return fallback;
}
