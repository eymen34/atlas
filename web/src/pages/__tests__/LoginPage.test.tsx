import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/authStore';
import { server } from '@/test/msw/server';
import { LoginPage } from '../LoginPage';

function renderLogin() {
  const qc = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/login']}>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function submitValidCredentials() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@b.com' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'whatever1' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  useAuthStore.getState().clearTokens();
});

afterEach(() => {
  sessionStorage.clear();
});

describe('LoginPage', () => {
  it('401 shows a non-enumerating message and never surfaces the raw backend message', async () => {
    server.use(
      http.post('/api/auth/login', () =>
        HttpResponse.json(
          {
            status: 401,
            error: 'Unauthorized',
            message: 'No user found for email a@b.com',
            path: '/api/auth/login',
          },
          { status: 401 }
        )
      )
    );
    renderLogin();
    submitValidCredentials();

    await waitFor(() =>
      expect(screen.getByTestId('form-error')).toHaveTextContent(/invalid email or password/i)
    );
    expect(screen.queryByText(/no user found/i)).toBeNull();
  });

  it('400 with an unknown body never surfaces the raw backend message at root', async () => {
    server.use(
      http.post('/api/auth/login', () =>
        HttpResponse.json({ message: 'RAW_BACKEND_INTERNAL_MSG' }, { status: 400 })
      )
    );
    renderLogin();
    submitValidCredentials();

    await waitFor(() => expect(screen.getByTestId('form-error')).toBeInTheDocument());
    expect(screen.queryByText('RAW_BACKEND_INTERNAL_MSG')).toBeNull();
  });

  it('client-side zod blocks an invalid email before any request', async () => {
    renderLogin();
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'not-an-email' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'whatever1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(0));
  });
});
