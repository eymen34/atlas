import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Ticket } from '@/api/tickets';
import { updateTicket } from '@/api/tickets';
import { TicketDescription } from '../TicketDescription';
import { renderWithProviders } from '@/test/test-utils';
import { TICKET_PROJ_1 } from './fixtures';

vi.mock('@/api/tickets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tickets')>();
  return { ...actual, updateTicket: vi.fn() };
});

const updateTicketMock = vi.mocked(updateTicket);

function renderDescription(ticket: Ticket) {
  return renderWithProviders(<TicketDescription idOrKey={ticket.key} ticket={ticket} />);
}

declare global {
  var __xss: number | undefined;
}

beforeEach(() => {
  updateTicketMock.mockReset();
  globalThis.__xss = undefined;
});

describe('TicketDescription (read mode)', () => {
  it('EC-6: renders the HTML through a read-only TipTap editor (editable:false)', async () => {
    renderDescription({ ...TICKET_PROJ_1, description: '<p>Hello</p>' });

    await waitFor(() => expect(document.querySelector('.ProseMirror')).not.toBeNull());
    const pm = document.querySelector('.ProseMirror') as HTMLElement;
    expect(pm.getAttribute('contenteditable')).toBe('false');
    expect(pm.textContent).toContain('Hello');
  });

  it('SEC-1: a script/onerror payload is neither executed nor present in the DOM', async () => {
    renderDescription({
      ...TICKET_PROJ_1,
      description: '<img src=x onerror="window.__xss=1"><script>window.__xss=2</script>',
    });

    await waitFor(() => expect(document.querySelector('.ProseMirror')).not.toBeNull());
    // TipTap parses through its schema (no script/image nodes) and never innerHTMLs
    // the raw string, so nothing executes and no <script> survives.
    expect(globalThis.__xss).toBeUndefined();
    expect(document.querySelector('.ProseMirror script')).toBeNull();
  });

  it('renders a placeholder when there is no description', () => {
    renderDescription({ ...TICKET_PROJ_1, description: '' });
    expect(screen.getByText('No description.')).toBeInTheDocument();
  });

  it('Cancel from edit mode discards without calling updateTicket', async () => {
    renderDescription({ ...TICKET_PROJ_1, description: '<p>Hello</p>' });

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    // The edit editor is lazy-loaded.
    await screen.findByTestId('ticket-description-editor');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    await screen.findByTestId('ticket-description-readonly');
    expect(updateTicketMock).not.toHaveBeenCalled();
  });
});
