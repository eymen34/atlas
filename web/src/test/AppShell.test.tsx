import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import { AppShell } from '@/layouts/AppShell';

function renderShell() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/login" element={<div>Login Stub</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AC-3.2 AppShell renders a shadcn Button in the header', () => {
  it('mounts at least one button inside the <header>', () => {
    renderShell();
    const buttons = screen.getAllByRole('button');
    expect(buttons.length).toBeGreaterThanOrEqual(1);
    const header = document.querySelector('header');
    expect(header).not.toBeNull();
    const buttonInHeader = buttons.some((b) => header!.contains(b));
    expect(buttonInHeader).toBe(true);
  });

  it('exposes the notifications button with the documented aria-label', () => {
    renderShell();
    const bell = screen.getByRole('button', { name: 'Notifications' });
    expect(bell).toBeInTheDocument();
  });

  it('renders the wrapped route content via <Outlet />', () => {
    renderShell();
    expect(screen.getByText('Login Stub')).toBeInTheDocument();
  });

  it('exposes a logout button and a topbar user slot (T-013)', () => {
    renderShell();
    expect(screen.getByTestId('logout-button')).toBeInTheDocument();
    expect(screen.getByTestId('topbar-user')).toBeInTheDocument();
  });
});
