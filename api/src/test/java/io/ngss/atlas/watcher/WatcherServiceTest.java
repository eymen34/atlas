package io.ngss.atlas.watcher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.ngss.atlas.config.FeatureFlags;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.TicketNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link WatcherService} (T-023): flag-gating + auto-watch branches. */
@ExtendWith(MockitoExtension.class)
class WatcherServiceTest {

  private static final UUID TICKET = UUID.randomUUID();
  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID CREATOR = UUID.randomUUID();
  private static final UUID ASSIGNEE = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-06-11T10:00:00Z");

  @Mock WatcherRepository watcherRepository;
  @Mock TicketRepository ticketRepository;
  @Mock ProjectAccessGuard guard;
  @Mock FeatureFlags featureFlags;
  @InjectMocks WatcherService service;

  private Ticket ticketWithId() {
    Ticket t = mock(Ticket.class);
    when(t.getId()).thenReturn(TICKET);
    return t;
  }

  @Test
  void autoWatchOnCreate_watchesCreatorAndDistinctAssignee() {
    when(featureFlags.watchersEnabled()).thenReturn(true);
    service.autoWatchOnCreate(ticketWithId(), CREATOR, ASSIGNEE, NOW);
    verify(watcherRepository).insertIgnoreConflict(any(), eq(TICKET), eq(CREATOR), eq(NOW));
    verify(watcherRepository).insertIgnoreConflict(any(), eq(TICKET), eq(ASSIGNEE), eq(NOW));
  }

  @Test
  void autoWatchOnCreate_creatorEqualsAssignee_insertsCreatorOnce() {
    when(featureFlags.watchersEnabled()).thenReturn(true);
    service.autoWatchOnCreate(ticketWithId(), CREATOR, CREATOR, NOW);
    verify(watcherRepository, times(1)).insertIgnoreConflict(any(), eq(TICKET), eq(CREATOR), eq(NOW));
  }

  @Test
  void autoWatchOnCreate_nullAssignee_insertsOnlyCreator() {
    when(featureFlags.watchersEnabled()).thenReturn(true);
    service.autoWatchOnCreate(ticketWithId(), CREATOR, null, NOW);
    verify(watcherRepository, times(1)).insertIgnoreConflict(any(), eq(TICKET), eq(CREATOR), eq(NOW));
  }

  @Test
  void autoWatch_flagOff_isNoOp() {
    when(featureFlags.watchersEnabled()).thenReturn(false);
    service.autoWatchOnCreate(mock(Ticket.class), CREATOR, ASSIGNEE, NOW);
    service.autoWatchAssignee(TICKET, ASSIGNEE, NOW);
    service.autoWatchCommenter(TICKET, CREATOR, NOW);
    verifyNoInteractions(watcherRepository);
  }

  @Test
  void watch_flagOff_throwsNotFound_withoutTouchingRepoOrGuard() {
    when(featureFlags.watchersEnabled()).thenReturn(false);
    assertThatThrownBy(() -> service.watch(TICKET, CREATOR))
        .isInstanceOf(TicketNotFoundException.class);
    verifyNoInteractions(watcherRepository);
    verifyNoInteractions(guard);
  }

  @Test
  void watch_flagOn_loadsGuardsAndInserts() {
    when(featureFlags.watchersEnabled()).thenReturn(true);
    Ticket t = mock(Ticket.class);
    when(t.getProjectId()).thenReturn(PROJECT);
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.of(t));

    service.watch(TICKET, CREATOR);

    verify(guard).requireMember(PROJECT);
    verify(watcherRepository).insertIgnoreConflict(any(), eq(TICKET), eq(CREATOR), any(Instant.class));
  }

  @Test
  void watch_ticketMissing_throwsNotFound() {
    when(featureFlags.watchersEnabled()).thenReturn(true);
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.watch(TICKET, CREATOR))
        .isInstanceOf(TicketNotFoundException.class);
    verify(watcherRepository, never()).insertIgnoreConflict(any(), any(), any(), any());
  }

  @Test
  void listWatcherIds_flagOff_throwsNotFound() {
    when(featureFlags.watchersEnabled()).thenReturn(false);
    assertThatThrownBy(() -> service.listWatcherIds(TICKET))
        .isInstanceOf(TicketNotFoundException.class);
  }
}
