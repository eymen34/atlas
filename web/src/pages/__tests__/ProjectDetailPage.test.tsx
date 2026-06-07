import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';
import { ProjectDetailPage } from '@/pages/ProjectDetailPage';
import { server } from '@/test/msw/server';
import { renderWithProviders } from '@/test/test-utils';

function renderDetail(initial: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectIdOrKey" element={<ProjectDetailPage />}>
        <Route index element={<div data-testid="outlet">OUTLET</div>} />
      </Route>
    </Routes>,
    { initialEntries: [initial] }
  );
}

describe('ProjectDetailPage', () => {
  it('renders the header (key + name) and the nested outlet on success', async () => {
    server.use(
      http.get('/api/projects/ALPHA', () =>
        HttpResponse.json({
          id: 'p1',
          key: 'ALPHA',
          name: 'Alpha Project',
          createdBy: 'u',
          createdAt: '',
          updatedAt: '',
          callerRole: 'ADMIN',
          memberCount: 1,
        })
      )
    );
    renderDetail('/projects/ALPHA');
    expect(await screen.findByText('Alpha Project')).toBeInTheDocument();
    expect(screen.getByText('ALPHA')).toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });

  it('shows a not-found state on 404', async () => {
    server.use(http.get('/api/projects/GHOST', () => new HttpResponse(null, { status: 404 })));
    renderDetail('/projects/GHOST');
    expect(await screen.findByTestId('project-not-found')).toBeInTheDocument();
  });
});
