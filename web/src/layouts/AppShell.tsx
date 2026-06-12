import { Outlet } from 'react-router';
import { useLogout } from '@/auth/useAuthMutations';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { NotificationBell } from '@/features/notifications/NotificationBell';
import { useAuthStore } from '@/store/authStore';

export function AppShell() {
  const logout = useLogout();
  const user = useAuthStore((s) => s.user);

  return (
    <div className="min-h-screen bg-background text-foreground" data-testid="app-shell">
      <header className="flex h-14 items-center justify-between border-b border-border px-6">
        <div className="flex items-center gap-2">
          <span className="text-lg font-semibold tracking-tight">Atlas</span>
        </div>
        <div className="flex items-center gap-3">
          <span data-testid="topbar-user" className="text-sm text-muted-foreground">
            {user?.displayName}
          </span>
          <NotificationBell />
          <Avatar>
            <AvatarFallback aria-label="User menu placeholder">
              {user?.displayName?.charAt(0)?.toUpperCase() ?? '??'}
            </AvatarFallback>
          </Avatar>
          <Button
            variant="outline"
            size="sm"
            type="button"
            data-testid="logout-button"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
          >
            Sign out
          </Button>
        </div>
      </header>
      <main className="px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}
