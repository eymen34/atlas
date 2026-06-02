import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuthStore } from '../store/authStore';

/**
 * Gate for authenticated routes. While booting (authenticating) a spinner is
 * shown so the UI never flashes to /login before the session is resolved.
 */
export function ProtectedRoute() {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();

  if (status === 'authenticating') {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="auth-spinner"
        className="flex min-h-screen items-center justify-center text-muted-foreground"
      >
        Loading…
      </div>
    );
  }

  if (status === 'authenticated') {
    return <Outlet />;
  }

  return <Navigate to="/login" replace state={{ from: location }} />;
}
