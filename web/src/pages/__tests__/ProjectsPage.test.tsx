import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { ProjectsPage } from '@/pages/ProjectsPage';
import { server } from '@/test/msw/server';
import { renderWithProviders } from '@/test/test-utils';

const oneProject = [
  {
    id: 'p1',
    key: 'ATLAS01',
    name: 'Atlas Project',
    description: 'A test project description',
    createdBy: 'u1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    callerRole: 'ADMIN',
    memberCount: 2,
  },
];

describe('ProjectsPage', () => {
  it('shows a loading skeleton before the list resolves', () => {
    server.use(http.get('/api/projects', () => HttpResponse.json([])));
    renderWithProviders(<ProjectsPage />);
    expect(screen.getByTestId('projects-loading')).toBeInTheDocument();
  });

  it('renders a card per project with key, name, and member count', async () => {
    server.use(http.get('/api/projects', () => HttpResponse.json(oneProject)));
    renderWithProviders(<ProjectsPage />);
    expect(await screen.findByText('ATLAS01')).toBeInTheDocument();
    expect(screen.getByText('Atlas Project')).toBeInTheDocument();
    expect(screen.getByText(/2 members/i)).toBeInTheDocument();
    expect(screen.getByTestId('project-card')).toBeInTheDocument();
  });

  it('renders an empty state with a create CTA when there are no projects', async () => {
    server.use(http.get('/api/projects', () => HttpResponse.json([])));
    renderWithProviders(<ProjectsPage />);
    expect(await screen.findByTestId('projects-empty')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create your first project/i })).toBeInTheDocument();
  });

  it('renders an error state when the request fails', async () => {
    server.use(http.get('/api/projects', () => new HttpResponse(null, { status: 500 })));
    renderWithProviders(<ProjectsPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load/i);
  });
});
