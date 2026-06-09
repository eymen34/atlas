package io.ngss.atlas.label;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.Label;
import io.ngss.atlas.label.dto.CreateLabelRequest;
import io.ngss.atlas.label.dto.UpdateLabelRequest;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LabelService} (Docker-free).
 *
 * <p>REG-8: deleting a label MUST remove its ticket_labels rows BEFORE the label
 * row, or the {@code ON DELETE NO ACTION} FK fires in production when associations
 * exist. The {@link InOrder} assertion locks that ordering at the service layer.
 */
@ExtendWith(MockitoExtension.class)
class LabelServiceDeleteOrderTest {

  @Mock LabelRepository labelRepository;
  @Mock TicketLabelRepository ticketLabelRepository;
  @Mock ProjectAccessGuard guard;
  @InjectMocks LabelService service;

  private static final UUID PROJECT = UUID.randomUUID();

  @Test
  void delete_removesTicketLabelsBeforeLabel() {
    UUID labelId = UUID.randomUUID();
    Label label = new Label(labelId, PROJECT, "Backend", null, Instant.now());
    when(labelRepository.findById(labelId)).thenReturn(Optional.of(label));

    service.delete(labelId);

    verify(guard).requireAdmin(PROJECT);
    InOrder order = inOrder(ticketLabelRepository, labelRepository);
    order.verify(ticketLabelRepository).deleteByLabelId(labelId);
    order.verify(labelRepository).delete(label);
  }

  @Test
  void create_duplicateNamePreCheck_throws409_andDoesNotSave() {
    when(labelRepository.findByProjectIdAndNameIgnoreCase(PROJECT, "backend"))
        .thenReturn(Optional.of(new Label(UUID.randomUUID(), PROJECT, "Backend", null, Instant.now())));

    assertThatThrownBy(() -> service.create(PROJECT, new CreateLabelRequest("backend", null)))
        .isInstanceOf(DuplicateLabelNameException.class);
    verify(guard).requireMember(PROJECT);
    verify(labelRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void update_bothFieldsNull_throwsValidation_afterMembership() {
    UUID labelId = UUID.randomUUID();
    Label label = new Label(labelId, PROJECT, "Backend", "#112233", Instant.now());
    when(labelRepository.findById(labelId)).thenReturn(Optional.of(label));

    assertThatThrownBy(() -> service.update(labelId, new UpdateLabelRequest(null, null)))
        .isInstanceOf(LabelValidationException.class);
    verify(guard).requireMember(PROJECT);
    verify(labelRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
  }
}
