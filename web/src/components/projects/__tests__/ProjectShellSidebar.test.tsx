import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Project } from '@/api/projects';
import { ProjectShellSidebar } from '@/components/projects/ProjectShellSidebar';
import { renderWithProviders } from '@/test/test-utils';

function projectFixture(callerRole: 'ADMIN' | 'MEMBER'): Project {
  return {
    id: 'p1',
    key: 'ALPHA',
    name: 'Alpha',
    createdBy: 'u1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    callerRole,
    memberCount: 1,
  };
}

describe('ProjectShellSidebar', () => {
  it('shows all four links for an ADMIN caller', () => {
    renderWithProviders(<ProjectShellSidebar project={projectFixture('ADMIN')} />);
    expect(screen.getByRole('link', { name: /board/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /list/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /members/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /settings/i })).toBeInTheDocument();
  });

  it('hides Members and Settings for a non-admin MEMBER (not rendered)', () => {
    renderWithProviders(<ProjectShellSidebar project={projectFixture('MEMBER')} />);
    expect(screen.getByRole('link', { name: /board/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /list/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /members/i })).toBeNull();
    expect(screen.queryByRole('link', { name: /settings/i })).toBeNull();
  });
});
