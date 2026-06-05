package io.ngss.atlas.security;

import io.ngss.atlas.domain.ProjectMember;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.ngss.atlas.domain.ProjectRole;
import io.ngss.atlas.project.ForbiddenProjectAccessException;
import io.ngss.atlas.project.ProjectNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Central project authorization (T-015). Resolves the current caller's
 * membership in a project and enforces the 404-vs-403 split:
 *
 * <ul>
 *   <li>{@link #requireMember} — non-member → {@link ProjectNotFoundException}
 *       (404, existence-leak prevention).
 *   <li>{@link #requireAdmin} — non-member → 404; member-but-not-admin →
 *       {@link ForbiddenProjectAccessException} (403).
 * </ul>
 *
 * <p>{@code @RequestScope}: one instance per HTTP request, with a per-request
 * memoization cache keyed by projectId, so a request that performs both a
 * visibility check and a capability check for the same project issues exactly
 * one {@code findByProjectIdAndUserId} query. Mutating services MUST call
 * {@link #invalidate} after changing membership so a later check in the same
 * request does not read a stale entry.
 *
 * <p>AppCDS stage-3 safe: the constructor touches no database, and a
 * {@code @RequestScope} bean is injected as a lazy CGLIB proxy — the real
 * instance is never created during the no-DB context-refresh boot.
 */
@Component
@RequestScope
public class ProjectAccessGuard {

  private final ProjectMemberRepository memberRepository;
  private final Map<UUID, Optional<ProjectMember>> cache = new HashMap<>();

  public ProjectAccessGuard(ProjectMemberRepository memberRepository) {
    this.memberRepository = memberRepository;
  }

  private Optional<ProjectMember> lookup(UUID projectId) {
    UUID userId = CurrentUser.id();
    return cache.computeIfAbsent(
        projectId, pid -> memberRepository.findByProjectIdAndUserId(pid, userId));
  }

  public boolean isMember(UUID projectId) {
    return lookup(projectId).isPresent();
  }

  public boolean isAdmin(UUID projectId) {
    return lookup(projectId).map(m -> m.getRole() == ProjectRole.ADMIN).orElse(false);
  }

  /** @throws ProjectNotFoundException if the caller is not a member (→ 404). */
  public void requireMember(UUID projectId) {
    if (!isMember(projectId)) {
      throw new ProjectNotFoundException(projectId);
    }
  }

  /**
   * @throws ProjectNotFoundException if the caller is not a member (→ 404)
   * @throws ForbiddenProjectAccessException if the caller is a member but not ADMIN (→ 403)
   */
  public void requireAdmin(UUID projectId) {
    if (!isMember(projectId)) {
      throw new ProjectNotFoundException(projectId);
    }
    if (!isAdmin(projectId)) {
      throw new ForbiddenProjectAccessException(projectId);
    }
  }

  /** Clears the cached membership for a project — call after any membership mutation. */
  public void invalidate(UUID projectId) {
    cache.remove(projectId);
  }
}
