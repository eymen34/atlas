import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/generated';
import { listMembers } from '@/api/projects';
import {
  getTicket,
  listLabels,
  listTicketActivity,
  type Ticket,
  ticketKeys,
  transitionTicket,
  updateTicket,
} from '@/api/tickets';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import TicketDetailPage from '@/pages/TicketDetailPage';
import { useAuthStore } from '@/store/authStore';
import { createTestQueryClient, renderWithProviders } from '@/test/test-utils';
import { LABELS_TWO, MEMBERS_TWO, TICKET_PROJ_1, TICKET_PROJ_1_UPDATED } from './fixtures';
import { toast } from 'sonner';

vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return {
    ...actual,
    getTicket: vi.fn(),
    updateTicket: vi.fn(),
    transitionTicket: vi.fn(),
    setTicketLabels: vi.fn(),
    listTicketActivity: vi.fn(),
    listLabels: vi.fn(),
  };
});
vi.mock('@/api/projects', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/projects')>();
  return { ...actual, listMembers: vi.fn() };
});
vi.mock('sonner', async (importOriginal) => {
  const actual = await importOriginal<typeof import('sonner')>();
  return { ...actual, toast: { error: vi.fn(), success: vi.fn() } };
});
// The description embeds TipTap; stub it so page tests don't depend on the editor
// (its real read-only/XSS behavior is covered in TicketDescription.test.tsx).
vi.mock('@/features/tickets/TicketDescription', () => ({
  TicketDescription: () => <div data-testid="ticket-description-readonly">description</div>,
}));

const getTicketMock = vi.mocked(getTicket);
const updateTicketMock = vi.mocked(updateTicket);
const transitionTicketMock = vi.mocked(transitionTicket);
const listActivityMock = vi.mocked(listTicketActivity);
const listMembersMock = vi.mocked(listMembers);
const listLabelsMock = vi.mocked(listLabels);

function apiError(status: number): ApiError {
  return new ApiError(
    { method: 'GET', url: '/api/tickets/{idOrKey}' } as never,
    { url: '', ok: false, status, statusText: 'Error', body: {} } as never,
    'Error'
  );
}

function renderPage(queryClient = createTestQueryClient()) {
  const utils = renderWithProviders(
    <Routes>
      <Route path="/projects/:projectIdOrKey/tickets/:key" element={<TicketDetailPage />} />
    </Routes>,
    { initialEntries: ['/projects/PROJ/tickets/PROJ-1'], queryClient }
  );
  return { ...utils, queryClient };
}

beforeEach(() => {
  getTicketMock.mockReset().mockResolvedValue(TICKET_PROJ_1);
  updateTicketMock.mockReset().mockResolvedValue(TICKET_PROJ_1_UPDATED);
  transitionTicketMock.mockReset().mockResolvedValue({ ...TICKET_PROJ_1, status: 'IN_PROGRESS' });
  listActivityMock.mockReset().mockResolvedValue([]);
  listMembersMock.mockReset().mockResolvedValue(MEMBERS_TWO);
  listLabelsMock.mockReset().mockResolvedValue(LABELS_TWO);
  vi.mocked(toast.error).mockClear();
  useAuthStore.setState({ status: 'authenticated' });
});

afterEach(() => {
  useAuthStore.setState({ status: 'authenticating' });
});

describe('TicketDetailPage', () => {
  it('AC1: renders the header, sidebar, description, and activity timeline', async () => {
    renderPage();

    expect(await screen.findByTestId('ticket-detail-page')).toBeInTheDocument();
    expect(screen.getByText('PROJ-1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Fix login bug' })).toBeInTheDocument();
    expect(screen.getByTestId('status-select')).toBeInTheDocument();
    expect(screen.getByTestId('ticket-description-readonly')).toBeInTheDocument();
    expect(screen.getByTestId('ticket-activity-timeline')).toBeInTheDocument();

    const sidebar = screen.getByTestId('ticket-sidebar');
    await waitFor(() =>
      expect(within(screen.getByTestId('assignee-picker')).getByText('Alice')).toBeInTheDocument()
    );
    // Reporter (u1 = Alice) renders read-only in the sidebar.
    expect(within(sidebar).getAllByText('Alice').length).toBeGreaterThanOrEqual(1);
  });

  it('AC-2.2: Enter commits a title edit via optimistic updateTicket PATCH', async () => {
    getTicketMock.mockReset().mockResolvedValueOnce(TICKET_PROJ_1).mockResolvedValue(TICKET_PROJ_1_UPDATED);
    const user = userEvent.setup();
    const { queryClient } = renderPage();

    await user.click(await screen.findByRole('button', { name: 'Fix login bug' }));
    const input = screen.getByLabelText('Ticket title');
    await user.clear(input);
    await user.type(input, 'Login fix');
    await user.keyboard('{Enter}');

    await waitFor(() =>
      expect(updateTicketMock).toHaveBeenCalledWith('t-uuid-1', { title: 'Login fix' })
    );
    expect(updateTicketMock).toHaveBeenCalledTimes(1);
    // Optimistic cache write before the server settles.
    await waitFor(() =>
      expect((queryClient.getQueryData(ticketKeys.detail('PROJ-1')) as Ticket).title).toBe('Login fix')
    );
    expect(await screen.findByRole('button', { name: 'Login fix' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Ticket title')).toBeNull();
  });

  it('AC-2.4: a failed title PATCH rolls back the cache and toasts (QG-5)', async () => {
    updateTicketMock.mockReset().mockRejectedValue(new Error('Server 500'));
    const user = userEvent.setup();
    const { queryClient } = renderPage();

    await user.click(await screen.findByRole('button', { name: 'Fix login bug' }));
    const input = screen.getByLabelText('Ticket title');
    await user.clear(input);
    await user.type(input, 'Bad title');
    await user.keyboard('{Enter}');

    await waitFor(() => expect(updateTicketMock).toHaveBeenCalled());
    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect((queryClient.getQueryData(ticketKeys.detail('PROJ-1')) as Ticket).title).toBe(
        'Fix login bug'
      )
    );
    expect(await screen.findByRole('button', { name: 'Fix login bug' })).toBeInTheDocument();
  });

  it('AC-3.1: a status change calls transitionTicket, NOT updateTicket (QG-6)', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('ticket-detail-page');

    await user.click(screen.getByTestId('status-select'));
    await user.click(await screen.findByRole('option', { name: 'In Progress' }));

    await waitFor(() =>
      expect(transitionTicketMock).toHaveBeenCalledWith('t-uuid-1', 'IN_PROGRESS')
    );
    expect(updateTicketMock).not.toHaveBeenCalled();
  });

  it('AC-3.2: a status transition invalidates the activity query', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderPage();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    await screen.findByTestId('ticket-detail-page');

    await user.click(screen.getByTestId('status-select'));
    await user.click(await screen.findByRole('option', { name: 'In Progress' }));

    await waitFor(() => expect(transitionTicketMock).toHaveBeenCalled());
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ticketKeys.activity('PROJ-1') })
    );
  });

  it('AC-4.2: an assignee change rolls back on error and reverts to the prior assignee', async () => {
    updateTicketMock.mockReset().mockRejectedValue(new Error('500'));
    const user = userEvent.setup();
    renderPage();

    const picker = await screen.findByTestId('assignee-picker');
    await waitFor(() => expect(within(picker).getByText('Alice')).toBeInTheDocument());

    await user.click(picker);
    await user.click(await screen.findByRole('option', { name: /Bob/ }));

    await waitFor(() =>
      expect(updateTicketMock).toHaveBeenCalledWith('t-uuid-1', { assigneeId: 'u2' })
    );
    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await waitFor(() =>
      expect(within(screen.getByTestId('assignee-picker')).getByText('Alice')).toBeInTheDocument()
    );
  });

  it('AC-5.3: a 404 from getTicket renders a friendly not-found state (no crash)', async () => {
    getTicketMock.mockReset().mockRejectedValue(apiError(404));
    renderPage();

    expect(await screen.findByTestId('ticket-not-found')).toBeInTheDocument();
    expect(screen.queryByTestId('ticket-detail-page')).toBeNull();
  });

  it('SEC-2: unauthenticated access redirects to /login and never fetches the ticket (QG-8)', async () => {
    useAuthStore.setState({ status: 'unauthenticated' });

    renderWithProviders(
      <Routes>
        <Route path="/login" element={<div data-testid="login-page">Login</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/projects/:projectIdOrKey/tickets/:key" element={<TicketDetailPage />} />
        </Route>
      </Routes>,
      { initialEntries: ['/projects/PROJ/tickets/PROJ-1'] }
    );

    expect(await screen.findByTestId('login-page')).toBeInTheDocument();
    expect(screen.queryByTestId('ticket-detail-page')).toBeNull();
    expect(getTicketMock).not.toHaveBeenCalled();
  });
});
