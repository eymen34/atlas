package io.ngss.atlas.project;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.project.dto.CreateProjectRequest;
import io.ngss.atlas.project.dto.ProjectResponse;
import io.ngss.atlas.project.dto.UpdateProjectRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Project aggregate (T-014).
 *
 * <p>All timestamps are stamped EXPLICITLY here via {@link Instant#now()} — there
 * is no DB trigger and no JPA {@code @PrePersist}/{@code @PreUpdate}. The V4
 * {@code DEFAULT now()} columns are only a safety net.
 *
 * <p>Authorization is creator-only for T-014: a caller may only see/mutate
 * projects where {@code created_by == callerId}. Non-creator access to an
 * existing project collapses to {@link ProjectNotFoundException} (→ 404) to
 * avoid leaking the project's existence (AC4).
 */
@Service
public class ProjectService {

  private final ProjectRepository repository;

  public ProjectService(ProjectRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public ProjectResponse create(CreateProjectRequest req, UUID callerId) {
    // Pre-check covers the common case; the partial unique index covers the race.
    if (repository.existsByKeyAndDeletedAtIsNull(req.key())) {
      throw new DuplicateProjectKeyException(req.key());
    }
    Instant now = Instant.now();
    // T-015 will additionally seed the creator as the first project_members admin row.
    Project project =
        new Project(
            UUID.randomUUID(),
            req.key(),
            req.name(),
            req.description(),
            callerId,
            now,
            now,
            null);
    try {
      repository.save(project);
    } catch (DataIntegrityViolationException e) {
      // Concurrent insert won the race against our pre-check → 409, not 500.
      throw new DuplicateProjectKeyException(req.key());
    }
    return ProjectResponse.from(project);
  }

  @Transactional(readOnly = true)
  public List<ProjectResponse> listForCaller(UUID callerId) {
    // T-015 will widen this from created_by-scoped to project_members-join-scoped.
    return repository.findByCreatedByAndDeletedAtIsNull(callerId).stream()
        .map(ProjectResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ProjectResponse getByIdOrKeyForCaller(String idOrKey, UUID callerId) {
    return ProjectResponse.from(loadForCaller(idOrKey, callerId));
  }

  @Transactional
  public ProjectResponse update(UUID id, UpdateProjectRequest req, UUID callerId) {
    Project project = loadOwnedByCaller(id, callerId);
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
  public void softDelete(UUID id, UUID callerId) {
    Project project = loadOwnedByCaller(id, callerId);
    project.softDelete(Instant.now());
    repository.save(project);
  }

  /** Resolves an id-or-key segment to a live project the caller owns, else 404. */
  private Project loadForCaller(String idOrKey, UUID callerId) {
    UUID id;
    try {
      id = UUID.fromString(idOrKey);
    } catch (IllegalArgumentException notAUuid) {
      return repository
          .findByKeyAndDeletedAtIsNull(idOrKey)
          .filter(p -> p.getCreatedBy().equals(callerId))
          .orElseThrow(ProjectNotFoundException::new);
    }
    return repository
        .findByIdAndDeletedAtIsNull(id)
        .filter(p -> p.getCreatedBy().equals(callerId))
        .orElseThrow(ProjectNotFoundException::new);
  }

  /** Loads a live project by id, requiring creator ownership; else 404 (no 403 — AC4). */
  private Project loadOwnedByCaller(UUID id, UUID callerId) {
    // T-015 will replace creator-only with project_members admin-role enforcement.
    return repository
        .findByIdAndDeletedAtIsNull(id)
        .filter(p -> p.getCreatedBy().equals(callerId))
        .orElseThrow(ProjectNotFoundException::new);
  }
}
