import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes, useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { NewProjectDialog } from '@/components/projects/NewProjectDialog';
import { server } from '@/test/msw/server';
import { renderWithProviders } from '@/test/test-utils';

function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname}</div>;
}

const noop = () => {};

describe('NewProjectDialog', () => {
  it('auto-suggests the key from the name until the key is edited', async () => {
    renderWithProviders(<NewProjectDialog open onOpenChange={noop} />);
    const name = screen.getByLabelText('Name');
    const key = screen.getByLabelText('Key') as HTMLInputElement;

    fireEvent.change(name, { target: { value: 'My New Project' } });
    await waitFor(() => expect(key.value).toBe('MYNEWPROJE'));

    fireEvent.change(key, { target: { value: 'CUSTOM1' } });
    fireEvent.change(name, { target: { value: 'Totally Different' } });
    await waitFor(() => expect(key.value).toBe('CUSTOM1'));
  });

  it('blocks a lowercase key client-side and dispatches no request', async () => {
    let posts = 0;
    server.use(
      http.post('/api/projects', () => {
        posts += 1;
        return HttpResponse.json({ id: 'x', key: 'X' }, { status: 201 });
      })
    );
    renderWithProviders(<NewProjectDialog open onOpenChange={noop} />);
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Valid Name' } });
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'atlas01' } });
    fireEvent.click(screen.getByRole('button', { name: /create project/i }));

    await waitFor(() => expect(screen.getByText(/key must be/i)).toBeInTheDocument());
    expect(posts).toBe(0);
  });

  it('navigates to the created project key on 201', async () => {
    server.use(
      http.post('/api/projects', () =>
        HttpResponse.json(
          {
            id: 'new',
            key: 'NEWPR1',
            name: 'New',
            createdBy: 'u',
            createdAt: '',
            updatedAt: '',
            callerRole: 'ADMIN',
            memberCount: 1,
          },
          { status: 201 }
        )
      )
    );
    renderWithProviders(
      <Routes>
        <Route path="/projects" element={<NewProjectDialog open onOpenChange={noop} />} />
        <Route path="/projects/:k" element={<LocationProbe />} />
      </Routes>,
      { initialEntries: ['/projects'] }
    );
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'New' } });
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'NEWPR1' } });
    fireEvent.click(screen.getByRole('button', { name: /create project/i }));

    expect(await screen.findByTestId('location')).toHaveTextContent('/projects/NEWPR1');
  });

  it('surfaces a 409 as a key field error and keeps the dialog open', async () => {
    server.use(
      http.post('/api/projects', () =>
        HttpResponse.json({ status: 409, message: "Project key 'NEWPR1' already in use" }, { status: 409 })
      )
    );
    renderWithProviders(<NewProjectDialog open onOpenChange={noop} />);
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'New' } });
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'NEWPR1' } });
    fireEvent.click(screen.getByRole('button', { name: /create project/i }));

    expect(await screen.findByText(/key already in use/i)).toBeInTheDocument();
    expect(screen.getByLabelText('Key')).toBeInTheDocument();
  });
});
