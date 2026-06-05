package io.ngss.atlas.project;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectMember;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectRole;
import io.ngss.atlas.project.dto.CreateProjectRequest;
import io.ngss.atlas.project.dto.ProjectResponse;
import io.ngss.atlas.project.dto.UpdateProjectRequest;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Project aggregate (T-014; authorization widened to
 * membership in T-015).
 *
 * <p>All timestamps are stamped EXPLICITLY here via {@link Instant#now()} — there
 * is no DB trigger and no JPA {@code @PrePersist}/{@code @PreUpdate}. The V4
 * {@code DEFAULT now()} columns are only a safety net.
 *
 * <p>Authorization lives exclusively in {@link ProjectAccessGuard}: visibility
 * (read) requires membership → 404 for non-members; mutation requires ADMIN →
 * 404 for non-members, 403 for member-non-admins. There are no {@code created_by}
 * checks here anymore.
 */
@Service
public class ProjectService {

  private final ProjectRepository repository;
  private final ProjectMemberRepository memberRepository;
  private final ProjectAccessGuard guard;

  public ProjectService(
      ProjectRepository repository,
      ProjectMemberRepository memberRepository,
      ProjectAccessGuard guard) {
    this.repository = repository;
    this.memberRepository = memberRepository;
    this.guard = guard;
  }

  @Transactional
  public ProjectResponse create(CreateProjectRequest req, UUID callerId) {
    // Pre-check covers the common case; the partial unique index covers the race.
    if (repository.existsByKeyAndDeletedAtIsNull(req.key())) {
      throw new DuplicateProjectKeyException(req.key());
    }
    Instant now = Instant.now();
    Project project =
        new Project(
            UUID.randomUUID(), req.key(), req.name(), req.description(), callerId, now, now, null);
    try {
      repository.save(project);
    } catch (DataIntegrityViolationException e) {
      // Concurrent insert won the race against our pre-check → 409, not 500.
      throw new DuplicateProjectKeyException(req.key());
    }
    // T-015: the creator becomes the project's first ADMIN, in the same transaction
    // (rolls back the project row if this fails).
    memberRepository.save(
        new ProjectMember(UUID.randomUUID(), project.getId(), callerId, ProjectRole.ADMIN, null, now));
    return ProjectResponse.from(project);
  }

  @Transactional(readOnly = true)
  public List<ProjectResponse> listForCaller(UUID callerId) {
    return repository.findLiveProjectsForMember(callerId).stream()
        .map(ProjectResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ProjectResponse getByIdOrKeyForCaller(String idOrKey) {
    Project project = loadByIdOrKey(idOrKey);
    guard.requireMember(project.getId());
    return ProjectResponse.from(project);
  }

  @Transactional
  public ProjectResponse update(UUID id, UpdateProjectRequest req) {
    Project project = loadLiveProject(id);
    guard.requireAdmin(project.getId());
    if (req.name() != null && req.name().isBlank()) {
      throw new ProjectValidationException("name must not be blank");
    }
    // null = unchanged; an explicit "" on description clears it.
    String newName = req.name() != null ? req.name() : project.getName();
    String newDescription = req.description() != null ? req.description() : project.getDescription();
    // PATCH always advances updatedAt, even when both fields are null (no-op patch).
    project.rename(newName, newDescription, Instant.now());
    repository.save(project);
    return ProjectResponse.from(project);
  }

  @Transactional
  public void softDelete(UUID id) {
    Project project = loadLiveProject(id);
    guard.requireAdmin(project.getId());
    project.softDelete(Instant.now());
    repository.save(project);
  }

  /** Resolves an id-or-key segment to a live project, else 404 (UUID first, then key). */
  private Project loadByIdOrKey(String idOrKey) {
    UUID id;
    try {
      id = UUID.fromString(idOrKey);
    } catch (IllegalArgumentException notAUuid) {
      return repository.findByKeyAndDeletedAtIsNull(idOrKey).orElseThrow(ProjectNotFoundException::new);
    }
    return repository.findByIdAndDeletedAtIsNull(id).orElseThrow(ProjectNotFoundException::new);
  }

  /** Loads a live project by id, else 404 (tombstoned/absent projects are not mutable). */
  private Project loadLiveProject(UUID id) {
    return repository.findByIdAndDeletedAtIsNull(id).orElseThrow(ProjectNotFoundException::new);
  }
}
