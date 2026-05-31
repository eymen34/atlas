import { OpenAPI } from './generated/core/OpenAPI';
import { AuthService } from './generated';
import { useAuthStore } from '../store/authStore';

/**
 * T-010 API client wrapper. Configures the generated openapi-typescript-codegen
 * client and exposes a 401-handling seam. The generated services
 * (AuthService, …) are re-exported so callers import from here, not from the
 * gitignored generated/ tree.
 */

// Same-origin base. The SPA is served by the Spring Boot app in prod, and the
// Vite dev server proxies /api/* to :8080, so an empty base keeps every
// generated call same-origin. Never hardcode a localhost URL here
// (architecture_decisions.frontend_build).
OpenAPI.BASE = '';

// Bearer injection. TOKEN is resolved per request, so it always reads the live
// Zustand state rather than a value captured at module load. Returning '' means
// the generated client adds no Authorization header.
OpenAPI.TOKEN = async () => useAuthStore.getState().accessToken ?? '';

// 401 handler seam. The default clears the in-memory tokens; a router-level
// guard (or T-011/T-012) can swap in a redirect-to-login via setOnUnauthorized.
// handleUnauthorized is the call site a future fetch wrapper invokes on a 401.
// Wiring it into the request pipeline is deferred: openapi-typescript-codegen
// 0.29.0's fetch client exposes no response-interceptor hook.
let onUnauthorizedHandler: () => void = () => {
  useAuthStore.getState().clearTokens();
};

export function setOnUnauthorized(handler: () => void): void {
  onUnauthorizedHandler = handler;
}

export function handleUnauthorized(): void {
  onUnauthorizedHandler();
}

export { AuthService };

// Explicit compile-time probe: forces tsc to validate that the generated
// AuthService exposes the get-current-user operation (operationId `getMe`).
// A bare re-export would not bind the symbol tightly enough to fail the build
// if codegen drifts or the operationId changes.
export const _getMeTypeProbe: typeof AuthService.getMe = AuthService.getMe;
