import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/authStore';
import { server } from '@/test/msw/server';
import { RegisterPage } from '../RegisterPage';

function renderRegister() {
  const qc = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/register']}>
        <RegisterPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  localStorage.clear();
  useAuthStore.getState().clearTokens();
});

describe('RegisterPage', () => {
  it('409 surfaces a field-level email error (not a root error)', async () => {
    server.use(
      http.post('/api/auth/register', () =>
        HttpResponse.json(
          { status: 409, error: 'Conflict', message: 'email already registered', path: '/api/auth/register' },
          { status: 409 }
        )
      )
    );
    renderRegister();
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'taken@example.com' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Bob' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: '0123456789' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() =>
      expect(screen.getByText(/account with this email already exists/i)).toBeInTheDocument()
    );
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('form-error')).toBeNull();
  });
});
