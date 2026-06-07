import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Outlet, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { Project } from '@/api/projects';
import { SettingsPage } from '@/pages/project/SettingsPage';
import { server } from '@/test/msw/server';
import { renderWithProviders } from '@/test/test-utils';

const PROJECT: Project = {
  id: 'p-uuid',
  key: 'ALPHA',
  name: 'Alpha',
  description: 'desc',
  createdBy: 'admin',
  createdAt: '',
  updatedAt: '',
  callerRole: 'ADMIN',
  memberCount: 1,
};

type RawMember = {
  userId: string;
  email: string;
  displayName: string;
  role: string;
  createdAt: string;
};

function ContextParent() {
  return <Outlet context={{ project: PROJECT }} />;
}

function renderSettings() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectIdOrKey" element={<ContextParent />}>
        <Route path="settings" element={<SettingsPage />} />
      </Route>
    </Routes>,
    { initialEntries: ['/projects/ALPHA/settings'] }
  );
}

const admin: RawMember = {
  userId: 'admin',
  email: 'admin@example.com',
  displayName: 'Admin',
  role: 'ADMIN',
  createdAt: '',
};

describe('SettingsPage', () => {
  it('adds a member and refetches the member list (dual invalidation)', async () => {
    const members: RawMember[] = [{ ...admin }];
    server.use(
      http.get('/api/projects/:id/members', () => HttpResponse.json(members)),
      http.post('/api/projects/:id/members', async ({ request }) => {
        const body = (await request.json()) as { email: string; role: string };
        const added: RawMember = {
          userId: 'bob',
          email: body.email,
          displayName: 'Bob',
          role: body.role,
          createdAt: '',
        };
        members.push(added);
        return HttpResponse.json(added, { status: 201 });
      })
    );

    renderSettings();
    expect(await screen.findByText('admin@example.com')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'bob@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /add member/i }));

    expect(await screen.findByText('bob@example.com')).toBeInTheDocument();
  });

  it('shows a toast when adding an unregistered email (404)', async () => {
    server.use(
      http.get('/api/projects/:id/members', () => HttpResponse.json([admin])),
      http.post('/api/projects/:id/members', () =>
        HttpResponse.json({ status: 404, message: 'No registered user with that email' }, { status: 404 })
      )
    );

    renderSettings();
    expect(await screen.findByText('admin@example.com')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'ghost@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /add member/i }));

    expect(await screen.findByText(/no registered user with that email/i)).toBeInTheDocument();
  });

  it('surfaces the backend 400 message when demoting the sole admin', async () => {
    server.use(
      http.get('/api/projects/:id/members', () => HttpResponse.json([admin])),
      http.patch('/api/projects/:id/members/:userId', () =>
        HttpResponse.json(
          { status: 400, message: 'Cannot demote the sole admin of a project' },
          { status: 400 }
        )
      )
    );

    renderSettings();
    expect(await screen.findByText('admin@example.com')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/role for admin@example.com/i), {
      target: { value: 'MEMBER' },
    });

    expect(await screen.findByText(/cannot demote the sole admin/i)).toBeInTheDocument();
  });
});
