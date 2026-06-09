package io.ngss.atlas.label;

import io.ngss.atlas.domain.Label;
import io.ngss.atlas.label.dto.CreateLabelRequest;
import io.ngss.atlas.label.dto.LabelResponse;
import io.ngss.atlas.label.dto.UpdateLabelRequest;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Label aggregate (T-018).
 *
 * <p>Authorization is delegated to {@link ProjectAccessGuard}, scoped to the
 * label's project. Project-addressed operations (list/create) guard the path's
 * project id directly — a non-existent project has no membership, so the guard
 * already yields 404. Label-addressed operations (update/delete) LOAD the label
 * first, then guard {@code label.projectId}. Read/create/update require membership
 * (non-member → 404); DELETE additionally requires ADMIN (403 for member-non-admin).
 *
 * <p>Name uniqueness (case-insensitive, per project) is enforced by a service
 * pre-check AND the V7 functional unique index. Writes use {@code saveAndFlush} so
 * a unique violation surfaces here as a {@link DuplicateLabelNameException} (409)
 * with the correct message, rather than escaping to commit time.
 */
@Service
public class LabelService {

  private static final String DUPLICATE_MESSAGE = "Label name already in use in this project";

  private final LabelRepository labelRepository;
  private final TicketLabelRepository ticketLabelRepository;
  private final ProjectAccessGuard guard;

  public LabelService(
      LabelRepository labelRepository,
      TicketLabelRepository ticketLabelRepository,
      ProjectAccessGuard guard) {
    this.labelRepository = labelRepository;
    this.ticketLabelRepository = ticketLabelRepository;
    this.guard = guard;
  }

  @Transactional(readOnly = true)
  public List<LabelResponse> list(UUID projectId) {
    guard.requireMember(projectId);
    return labelRepository.findByProjectIdOrderByNameAsc(projectId).stream()
        .map(LabelResponse::from)
        .toList();
  }

  @Transactional
  public LabelResponse create(UUID projectId, CreateLabelRequest req) {
    guard.requireMember(projectId);
    // Pre-check covers the common case; the functional unique index covers the race.
    if (labelRepository.findByProjectIdAndNameIgnoreCase(projectId, req.name()).isPresent()) {
      throw new DuplicateLabelNameException(DUPLICATE_MESSAGE);
    }
    Label label = new Label(UUID.randomUUID(), projectId, req.name(), req.color(), Instant.now());
    try {
      labelRepository.saveAndFlush(label);
    } catch (DataIntegrityViolationException race) {
      // Concurrent insert won the race against our pre-check → 409, not 500.
      throw new DuplicateLabelNameException(DUPLICATE_MESSAGE);
    }
    return LabelResponse.from(label);
  }

  @Transactional
  public LabelResponse update(UUID labelId, UpdateLabelRequest req) {
    Label label = loadLabel(labelId);
    guard.requireMember(label.getProjectId());
    if (req.name() == null && req.color() == null) {
      throw new LabelValidationException("at least one of name or color must be provided");
    }
    // null = unchanged on each field.
    String newName = req.name() != null ? req.name() : label.getName();
    String newColor = req.color() != null ? req.color() : label.getColor();
    label.updateNameColor(newName, newColor);
    try {
      labelRepository.saveAndFlush(label);
    } catch (DataIntegrityViolationException dup) {
      // Rename collided with an existing label in the project.
      throw new DuplicateLabelNameException(DUPLICATE_MESSAGE);
    }
    return LabelResponse.from(label);
  }

  @Transactional
  public void delete(UUID labelId) {
    Label label = loadLabel(labelId);
    // DELETE requires ADMIN: non-member → 404, member-non-admin → 403.
    guard.requireAdmin(label.getProjectId());
    // MANDATORY order: remove the ticket_labels associations FIRST (bulk JPQL DELETE,
    // executed immediately), THEN the label row — otherwise the ON DELETE NO ACTION
    // FK from ticket_labels.label_id would be violated.
    ticketLabelRepository.deleteByLabelId(labelId);
    labelRepository.delete(label);
  }

  private Label loadLabel(UUID labelId) {
    return labelRepository
        .findById(labelId)
        .orElseThrow(() -> new LabelNotFoundException("label not found: " + labelId));
  }
}
