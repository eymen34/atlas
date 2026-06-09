import {
  AddMemberRequest,
  type CreateProjectRequest,
  ProjectMembersService,
  type ProjectResponse,
  ProjectsService,
  UpdateMemberRoleRequest,
  type UpdateProjectRequest,
} from './generated';

// T-020: the error helpers moved to a shared module; re-exported here so existing
// importers (`from '@/api/projects'`) keep working unchanged.
export { apiErrorStatus, apiErrorMessage } from './errors';

/**
 * T-016 app-facing project/member API.
 *
 * The generated client models every field as optional and splits the single
 * backend `ProjectRole` into four namespaced string enums (one per DTO). This
 * thin layer normalizes both: it maps responses into required, app-friendly
 * shapes and exposes a single {@link ProjectRole} literal union so the ~8 UI
 * components never touch the generated namespaces. The generated request layer
 * throws {@code ApiError} (status + body) on non-2xx, which the mutation
 * handlers switch on.
 */
export type ProjectRole = 'MEMBER' | 'ADMIN';

export interface Project {
  id: string;
  key: string;
  name: string;
  description?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  callerRole: ProjectRole;
  memberCount: number;
}

export interface Member {
  userId: string;
  email: string;
  displayName: string;
  role: ProjectRole;
  invitedBy?: string;
  createdAt: string;
  /** Not currently sent by the backend; reserved for actor avatars (T-021). */
  avatarUrl?: string;
}

/** Total over the role enum: anything that is not exactly ADMIN is MEMBER. */
function asRole(value: unknown): ProjectRole {
  return value === 'ADMIN' ? 'ADMIN' : 'MEMBER';
}

function toProject(r: ProjectResponse): Project {
  return {
    id: r.id ?? '',
    key: r.key ?? '',
    name: r.name ?? '',
    description: r.description,
    createdBy: r.createdBy ?? '',
    createdAt: r.createdAt ?? '',
    updatedAt: r.updatedAt ?? '',
    callerRole: asRole(r.callerRole),
    memberCount: r.memberCount ?? 0,
  };
}

interface RawMember {
  userId?: string;
  email?: string;
  displayName?: string;
  role?: unknown;
  invitedBy?: string;
  createdAt?: string;
}

function toMember(m: RawMember): Member {
  return {
    userId: m.userId ?? '',
    email: m.email ?? '',
    displayName: m.displayName ?? '',
    role: asRole(m.role),
    invitedBy: m.invitedBy,
    createdAt: m.createdAt ?? '',
  };
}

function toAddRole(role: ProjectRole): AddMemberRequest.role {
  return role === 'ADMIN' ? AddMemberRequest.role.ADMIN : AddMemberRequest.role.MEMBER;
}

function toUpdateRole(role: ProjectRole): UpdateMemberRoleRequest.role {
  return role === 'ADMIN' ? UpdateMemberRoleRequest.role.ADMIN : UpdateMemberRoleRequest.role.MEMBER;
}

/** TanStack Query keys — detail and members are nested under the same prefix. */
export const projectKeys = {
  list: ['projects'] as const,
  detail: (idOrKey: string) => ['project', idOrKey] as const,
  members: (idOrKey: string) => ['project', idOrKey, 'members'] as const,
};

export async function listProjects(): Promise<Project[]> {
  return (await ProjectsService.list()).map(toProject);
}

export async function getProject(idOrKey: string): Promise<Project> {
  return toProject(await ProjectsService.get(idOrKey));
}

export async function createProject(input: CreateProjectRequest): Promise<Project> {
  return toProject(await ProjectsService.create(input));
}

export async function updateProject(id: string, input: UpdateProjectRequest): Promise<Project> {
  return toProject(await ProjectsService.update(id, input));
}

export async function listMembers(idOrKey: string): Promise<Member[]> {
  return (await ProjectMembersService.list1(idOrKey)).map(toMember);
}

export async function addMember(idOrKey: string, email: string, role: ProjectRole): Promise<Member> {
  return toMember(await ProjectMembersService.add(idOrKey, { email, role: toAddRole(role) }));
}

export async function changeMemberRole(
  idOrKey: string,
  userId: string,
  role: ProjectRole
): Promise<Member> {
  return toMember(
    await ProjectMembersService.changeRole(idOrKey, userId, { role: toUpdateRole(role) })
  );
}

export async function removeMember(idOrKey: string, userId: string): Promise<void> {
  await ProjectMembersService.remove(idOrKey, userId);
}
