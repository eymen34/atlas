import { useOutletContext } from 'react-router';
import type { Project } from '@/api/projects';

export interface ProjectOutletContext {
  project: Project;
}

/** Reads the project resolved by ProjectDetailPage, shared with nested routes. */
export function useProjectOutlet(): ProjectOutletContext {
  return useOutletContext<ProjectOutletContext>();
}
