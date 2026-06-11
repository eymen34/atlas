package io.ngss.atlas.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectTicketCounterRepository;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.label.LabelRepository;
import io.ngss.atlas.label.TicketLabelRepository;
import io.ngss.atlas.project.ProjectNotFoundException;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.dto.CreateTicketRequest;
import io.ngss.atlas.ticket.dto.TicketResponse;
import io.ngss.atlas.ticket.dto.TransitionRequest;
import io.ngss.atlas.ticket.dto.UpdateTicketRequest;
import io.ngss.atlas.ticket.event.TicketTransitionedEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link TicketService} — number assignment, priority defaulting
 * (FIX 1), id-or-key resolution incl. digit-bearing project keys (FIX 2),
 * blank-title validation (D2), and the transition no-op-publishes-no-event rule.
 * These run WITHOUT Docker (the numbering/concurrency ITs cover the real DB).
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

  @Mock TicketRepository ticketRepository;
  @Mock ProjectTicketCounterRepository counterRepository;
  @Mock ProjectRepository projectRepository;
  @Mock LabelRepository labelRepository;
  @Mock TicketLabelRepository ticketLabelRepository;
  @Mock ProjectAccessGuard guard;
  @Mock ApplicationEventPublisher eventPublisher;
  @Mock EntityManager entityManager;
  @Mock ActivityEventWriter activityWriter;
  @Mock io.ngss.atlas.mention.MentionParser mentionParser;
  @Mock io.ngss.atlas.comment.TicketMentionRepository ticketMentionRepository;
  @Mock io.ngss.atlas.watcher.WatcherService watcherService;
  @InjectMocks TicketService service;

  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();

  private Project liveProject(String key) {
    Instant t = Instant.now();
    return new Project(PROJECT, key, "Name", null, CALLER, t, t, null);
  }

  private Ticket ticket(UUID id, int number, TicketStatus status) {
    Instant t = Instant.now();
    return new Ticket(
        id, PROJECT, number, "Title", "Desc", status, TicketPriority.P2, null, CALLER, t, t, null);
  }

  // ───────────────────────── create ─────────────────────────

  @Test
  void create_defaultsPriorityToP2_whenOmitted_andAssignsClaimedNumber() {
    when(projectRepository.findByIdAndDeletedAtIsNull(PROJECT)).thenReturn(Optional.of(liveProject("ENG")));
    when(counterRepository.claimNextNumber(PROJECT)).thenReturn(1);

    TicketResponse resp =
        service.create(PROJECT, new CreateTicketRequest("Build it", "body", null, null), CALLER);

    assertThat(resp.id()).isNotNull();
    assertThat(resp.number()).isEqualTo(1);
    assertThat(resp.key()).isEqualTo("ENG-1");
    assertThat(resp.title()).isEqualTo("Build it");
    assertThat(resp.status()).isEqualTo(TicketStatus.TODO);
    assertThat(resp.priority()).isEqualTo(TicketPriority.P2);
    assertThat(resp.reporterId()).isEqualTo(CALLER);
    assertThat(resp.createdAt()).isEqualTo(resp.updatedAt());
    verify(guard).requireMember(PROJECT);
    verify(ticketRepository).save(any(Ticket.class));
  }

  @Test
  void create_usesProvidedPriority_whenPresent() {
    when(projectRepository.findByIdAndDeletedAtIsNull(PROJECT)).thenReturn(Optional.of(liveProject("ENG")));
    when(counterRepository.claimNextNumber(PROJECT)).thenReturn(7);

    TicketResponse resp =
        service.create(
            PROJECT, new CreateTicketRequest("Urgent", null, TicketPriority.P0, CALLER), CALLER);

    // FIX 1: a supplied priority must be kept (the BLOCKED revision inverted this).
    assertThat(resp.priority()).isEqualTo(TicketPriority.P0);
    assertThat(resp.number()).isEqualTo(7);
    assertThat(resp.key()).isEqualTo("ENG-7");
    assertThat(resp.assigneeId()).isEqualTo(CALLER);
  }

  @Test
  void create_softDeletedOrMissingProject_throwsNotFound_andClaimsNoNumber() {
    when(projectRepository.findByIdAndDeletedAtIsNull(PROJECT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.create(PROJECT, new CreateTicketRequest("x", null, null, null), CALLER))
        .isInstanceOf(ProjectNotFoundException.class);
    verify(counterRepository, never()).claimNextNumber(any());
    verify(ticketRepository, never()).save(any());
  }

  // ───────────────────────── getByIdOrKey ─────────────────────────

  @Test
  void getByIdOrKey_digitBearingKey_routesToKeyFinder_splitOnLastHyphen() {
    UUID ticketId = UUID.randomUUID();
    // FIX 2: ENG2-42 → projectKey "ENG2", number 42 (split on the LAST hyphen).
    when(ticketRepository.findByProjectKeyAndNumberAndDeletedAtIsNull("ENG2", 42))
        .thenReturn(Optional.of(ticket(ticketId, 42, TicketStatus.TODO)));

    TicketResponse resp = service.getByIdOrKey("ENG2-42");

    assertThat(resp.number()).isEqualTo(42);
    assertThat(resp.key()).isEqualTo("ENG2-42");
    verify(guard).requireMember(PROJECT);
    verify(ticketRepository, never()).findByIdAndDeletedAtIsNull(any());
  }

  @Test
  void getByIdOrKey_uuid_routesToIdFinder() {
    UUID ticketId = UUID.randomUUID();
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId))
        .thenReturn(Optional.of(ticket(ticketId, 5, TicketStatus.TODO)));
    when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(liveProject("OPS")));

    TicketResponse resp = service.getByIdOrKey(ticketId.toString());

    assertThat(resp.key()).isEqualTo("OPS-5");
    verify(guard).requireMember(PROJECT);
    verify(ticketRepository, never()).findByProjectKeyAndNumberAndDeletedAtIsNull(any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void getByIdOrKey_garbageSegment_throwsNotFound_withoutGuard() {
    assertThatThrownBy(() -> service.getByIdOrKey("not-a-uuid-or-key"))
        .isInstanceOf(TicketNotFoundException.class);
    verifyNoInteractions(guard);
  }

  @Test
  void getByIdOrKey_keyNotFound_throwsNotFound() {
    when(ticketRepository.findByProjectKeyAndNumberAndDeletedAtIsNull("ENG", 99))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getByIdOrKey("ENG-99"))
        .isInstanceOf(TicketNotFoundException.class);
  }

  // ───────────────────────── update ─────────────────────────

  @Test
  void update_blankTitle_throwsValidation_afterMembership() {
    UUID ticketId = UUID.randomUUID();
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId))
        .thenReturn(Optional.of(ticket(ticketId, 1, TicketStatus.TODO)));

    assertThatThrownBy(
            () -> service.update(ticketId, new UpdateTicketRequest("   ", null, null, null), CALLER))
        .isInstanceOf(TicketValidationException.class);
    verify(guard).requireMember(PROJECT);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void update_nullFields_leaveValuesUnchanged_butStatusNeverChanges() {
    UUID ticketId = UUID.randomUUID();
    Ticket existing = ticket(ticketId, 1, TicketStatus.IN_PROGRESS);
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId)).thenReturn(Optional.of(existing));
    when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(liveProject("ENG")));

    TicketResponse resp =
        service.update(ticketId, new UpdateTicketRequest(null, null, null, null), CALLER);

    assertThat(resp.title()).isEqualTo("Title");
    assertThat(resp.description()).isEqualTo("Desc");
    assertThat(resp.status()).isEqualTo(TicketStatus.IN_PROGRESS);
    verify(ticketRepository).save(existing);
  }

  // ───────────────────────── transition ─────────────────────────

  @Test
  void transition_realChange_publishesEventWithFromToActor() {
    UUID ticketId = UUID.randomUUID();
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId))
        .thenReturn(Optional.of(ticket(ticketId, 1, TicketStatus.TODO)));
    when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(liveProject("ENG")));

    TicketResponse resp =
        service.transition(ticketId, new TransitionRequest(TicketStatus.IN_PROGRESS), CALLER);

    assertThat(resp.status()).isEqualTo(TicketStatus.IN_PROGRESS);
    ArgumentCaptor<TicketTransitionedEvent> captor =
        ArgumentCaptor.forClass(TicketTransitionedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    TicketTransitionedEvent event = captor.getValue();
    assertThat(event.ticketId()).isEqualTo(ticketId);
    assertThat(event.projectId()).isEqualTo(PROJECT);
    assertThat(event.fromStatus()).isEqualTo(TicketStatus.TODO);
    assertThat(event.toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(event.actorId()).isEqualTo(CALLER);
  }

  @Test
  void transition_sameStatus_isNoOp_publishesNoEvent_andDoesNotSave() {
    UUID ticketId = UUID.randomUUID();
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId))
        .thenReturn(Optional.of(ticket(ticketId, 1, TicketStatus.TODO)));
    when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(liveProject("ENG")));

    TicketResponse resp =
        service.transition(ticketId, new TransitionRequest(TicketStatus.TODO), CALLER);

    assertThat(resp.status()).isEqualTo(TicketStatus.TODO);
    verify(eventPublisher, never()).publishEvent(any());
    verify(ticketRepository, never()).save(any());
  }

  // ───────────────────────── softDelete ─────────────────────────

  @Test
  void softDelete_requiresAdmin_thenStampsDeletedAt() {
    UUID ticketId = UUID.randomUUID();
    Ticket existing = ticket(ticketId, 1, TicketStatus.TODO);
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId)).thenReturn(Optional.of(existing));

    service.softDelete(ticketId);

    assertThat(existing.getDeletedAt()).isNotNull();
    verify(guard).requireAdmin(PROJECT);
    verify(ticketRepository).save(existing);
  }
}
