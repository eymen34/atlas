import { Bell } from 'lucide-react';
import { Outlet } from 'react-router';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';

export function AppShell() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="flex h-14 items-center justify-between border-b border-border px-6">
        <div className="flex items-center gap-2">
          <span className="text-lg font-semibold tracking-tight">Atlas</span>
        </div>
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            aria-label="Notifications"
            type="button"
          >
            <Bell className="h-5 w-5" aria-hidden="true" />
          </Button>
          <Avatar>
            <AvatarFallback aria-label="User menu placeholder">??</AvatarFallback>
          </Avatar>
        </div>
      </header>
      <main className="px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}
