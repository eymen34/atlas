import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router';
import { Toaster } from '@/components/ui/sonner';
import { AuthProvider } from './auth/AuthProvider';
import { AuthRedirect } from './auth/AuthRedirect';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { AppShell } from './layouts/AppShell';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import TicketDetailPage from './pages/TicketDetailPage';
import { BoardPage } from './pages/project/BoardPage';
import { ListPage } from './pages/project/ListPage';
import { MembersPage } from './pages/project/MembersPage';
import { SettingsPage } from './pages/project/SettingsPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      {/* Toaster at the root so /login and /register can surface toasts (T-013). */}
      <Toaster richColors position="top-right" />
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route element={<AuthRedirect />}>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />
            </Route>
            <Route element={<ProtectedRoute />}>
              <Route element={<AppShell />}>
                <Route path="/projects" element={<ProjectsPage />} />
                <Route path="/projects/:projectIdOrKey" element={<ProjectDetailPage />}>
                  <Route index element={<BoardPage />} />
                  <Route path="board" element={<BoardPage />} />
                  <Route path="list" element={<ListPage />} />
                  <Route path="members" element={<MembersPage />} />
                  <Route path="settings" element={<SettingsPage />} />
                </Route>
                {/* Ticket detail is a SIBLING of the project shell (own data fetch,
                    no project outlet context), still inside ProtectedRoute+AppShell. */}
                <Route
                  path="/projects/:projectIdOrKey/tickets/:key"
                  element={<TicketDetailPage />}
                />
              </Route>
            </Route>
            <Route path="*" element={<Navigate to="/projects" replace />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
