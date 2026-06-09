package io.ngss.atlas.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.ActivityEvent;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit test for {@link ActivityController}'s page/size clamping (Docker-free,
 * Mockito — no MockMvc, which is not on the Boot 4 test classpath). Captures the
 * {@link Pageable} passed to the repository and asserts the clamp.
 */
@ExtendWith(MockitoExtension.class)
class ActivityControllerSizeClampTest {

  @Mock TicketRepository ticketRepository;
  @Mock ActivityEventRepository activityRepository;
  @Mock ProjectAccessGuard guard;
  @Mock ObjectMapper objectMapper;
  @InjectMocks ActivityController controller;

  @Captor ArgumentCaptor<Pageable> pageableCaptor;

  private final UUID ticketId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();

  @BeforeEach
  void stubLoadAndQuery() {
    Ticket ticket =
        new Ticket(
            ticketId, projectId, 1, "t", null, TicketStatus.TODO, TicketPriority.P2, null,
            UUID.randomUUID(), Instant.now(), Instant.now(), null);
    when(ticketRepository.findByIdAndDeletedAtIsNull(ticketId)).thenReturn(Optional.of(ticket));
    when(activityRepository.findByTicketIdOrderByCreatedAtDesc(eq(ticketId), any()))
        .thenReturn(Page.<ActivityEvent>empty());
  }

  @ParameterizedTest
  @CsvSource({"0, 1", "-1, 1", "1, 1", "20, 20", "100, 100", "101, 100", "999, 100"})
  void sizeIsClampedToOneHundred(int requested, int expected) {
    controller.listTicketActivity(ticketId, 0, requested);
    verify(activityRepository)
        .findByTicketIdOrderByCreatedAtDesc(eq(ticketId), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"-1, 0", "0, 0", "5, 5"})
  void pageIsClampedToNonNegative(int requested, int expected) {
    controller.listTicketActivity(ticketId, requested, 20);
    verify(activityRepository)
        .findByTicketIdOrderByCreatedAtDesc(eq(ticketId), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(expected);
  }
}
