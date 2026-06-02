import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuthStore } from '../store/authStore';

/**
 * Inverse of ProtectedRoute: keeps an already-authenticated user out of
 * /login and /register, sending them to where they came from (or /projects).
 */
export function AuthRedirect() {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();

  if (status === 'authenticated') {
    const from =
      (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? '/projects';
    return <Navigate to={from} replace />;
  }

  return <Outlet />;
}
