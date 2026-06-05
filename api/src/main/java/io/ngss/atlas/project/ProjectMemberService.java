package io.ngss.atlas.project;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectMember;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectRole;
import io.ngss.atlas.domain.User;
import io.ngss.atlas.domain.UserRepository;
import io.ngss.atlas.project.dto.AddMemberRequest;
import io.ngss.atlas.project.dto.MemberResponse;
import io.ngss.atlas.project.dto.UpdateMemberRoleRequest;
import io.ngss.atlas.security.CurrentUser;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member-management service for the Project aggregate (T-015).
 *
 * <p>Every method first resolves the LIVE project (404 if absent/soft-deleted),
 * then enforces authorization via {@link ProjectAccessGuard}: read paths require
 * membership, mutation paths require ADMIN. The last-admin invariant is held
 * race-safe by pessimistically locking the project's admin rows before counting.
 */
@Service
public class ProjectMemberService {

  private final ProjectMemberRepository memberRepository;
  private final UserRepository userRepository;
  private final ProjectRepository projectRepository;
  private final ProjectAccessGuard guard;

  public ProjectMemberService(
      ProjectMemberRepository memberRepository,
      UserRepository userRepository,
      ProjectRepository projectRepository,
      ProjectAccessGuard guard) {
    this.memberRepository = memberRepository;
    this.userRepository = userRepository;
    this.projectRepository = projectRepository;
    this.guard = guard;
  }

  @Transactional(readOnly = true)
  public List<MemberResponse> listMembers(UUID projectId) {
    loadLiveProject(projectId);
    guard.requireMember(projectId);
    return memberRepository.findMemberResponsesByProjectId(projectId);
  }

  @Transactional
  public MemberResponse addMember(UUID projectId, AddMemberRequest req) {
    loadLiveProject(projectId);
    guard.requireAdmin(projectId);
    User user =
        userRepository
            .findByEmailIgnoreCase(req.email())
            .orElseThrow(() -> new UserNotFoundException("No registered user for that email"));
    if (memberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
      throw new DuplicateMemberException(projectId, user.getId());
    }
    ProjectMember member =
        new ProjectMember(
            UUID.randomUUID(), projectId, user.getId(), req.role(), CurrentUser.id(), Instant.now());
    try {
      memberRepository.save(member);
    } catch (DataIntegrityViolationException race) {
      // Lost the UNIQUE(project_id,user_id) race against a concurrent add → 409.
      throw new DuplicateMemberException(projectId, user.getId());
    }
    guard.invalidate(projectId);
    return new MemberResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        member.getRole(),
        member.getInvitedBy(),
        member.getCreatedAt());
  }

  @Transactional
  public MemberResponse changeRole(UUID projectId, UUID targetUserId, UpdateMemberRoleRequest req) {
    loadLiveProject(projectId);
    guard.requireAdmin(projectId);
    ProjectMember target =
        memberRepository
            .findByProjectIdAndUserId(projectId, targetUserId)
            .orElseThrow(() -> new MemberNotFoundException(projectId, targetUserId));
    if (target.getRole() == ProjectRole.ADMIN && req.role() == ProjectRole.MEMBER) {
      requireNotLastAdmin(projectId);
    }
    target.changeRole(req.role());
    memberRepository.save(target);
    guard.invalidate(projectId);
    User user = userRepository.findById(targetUserId).orElseThrow();
    return new MemberResponse(
        targetUserId,
        user.getEmail(),
        user.getDisplayName(),
        target.getRole(),
        target.getInvitedBy(),
        target.getCreatedAt());
  }

  @Transactional
  public void removeMember(UUID projectId, UUID targetUserId) {
    loadLiveProject(projectId);
    guard.requireAdmin(projectId);
    ProjectMember target =
        memberRepository
            .findByProjectIdAndUserId(projectId, targetUserId)
            .orElseThrow(() -> new MemberNotFoundException(projectId, targetUserId));
    if (target.getRole() == ProjectRole.ADMIN) {
      requireNotLastAdmin(projectId);
    }
    memberRepository.delete(target);
    guard.invalidate(projectId);
    // NOTE: self-removal ("leave project") by a non-admin is intentionally out of
    // scope for T-015 (this path is admin-only). Backlog: allow a member to remove
    // themselves when they are not the last admin.
  }

  /** Pessimistically locks the project's admin rows, then enforces ≥1 will remain. */
  private void requireNotLastAdmin(UUID projectId) {
    if (memberRepository.lockAdminsForProject(projectId).size() <= 1) {
      throw new LastAdminException(projectId);
    }
  }

  private Project loadLiveProject(UUID projectId) {
    return projectRepository
        .findByIdAndDeletedAtIsNull(projectId)
        .orElseThrow(() -> new ProjectNotFoundException(projectId));
  }
}
