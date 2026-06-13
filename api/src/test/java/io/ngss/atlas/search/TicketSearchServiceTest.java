package io.ngss.atlas.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.search.dto.TicketSearchResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TicketSearchService} (Docker-free). Locks the paging math (size clamp
 * 1..100, page floored at 0, offset = page*size) and the row→result mapping (ticketKey assembly,
 * status parse) — the pure logic the ITs can't isolate from Postgres.
 */
@ExtendWith(MockitoExtension.class)
class TicketSearchServiceTest {

  @Mock TicketSearchRepository repository;
  @InjectMocks TicketSearchService service;

  private static final UUID PROJECT = UUID.randomUUID();

  @Test
  void clampsSizeBelowOneToOne() {
    when(repository.searchProject(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    when(repository.countProject(any(), any())).thenReturn(0L);

    service.searchProject(PROJECT, "q", 0, 0);

    verify(repository).searchProject(eq(PROJECT), eq("q"), eq(1), eq(0)); // 0 → 1
  }

  @Test
  void clampsSizeAbove100To100() {
    when(repository.searchProject(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    when(repository.countProject(any(), any())).thenReturn(0L);

    service.searchProject(PROJECT, "q", 0, 500);

    verify(repository).searchProject(eq(PROJECT), eq("q"), eq(100), eq(0)); // 500 → 100
  }

  @Test
  void computesOffsetFromClampedPageAndSize() {
    when(repository.searchProject(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    when(repository.countProject(any(), any())).thenReturn(0L);

    service.searchProject(PROJECT, "q", 3, 10);

    verify(repository).searchProject(eq(PROJECT), eq("q"), eq(10), eq(30)); // offset = 3*10
  }

  @Test
  void negativePageIsFlooredToZero() {
    when(repository.searchProject(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    when(repository.countProject(any(), any())).thenReturn(0L);

    PagedResponse<TicketSearchResult> result = service.searchProject(PROJECT, "q", -5, 25);

    assertThat(result.page()).isZero();
    verify(repository).searchProject(eq(PROJECT), eq("q"), eq(25), eq(0)); // offset = 0
  }

  @Test
  void mapsRowAssemblingTicketKeyAndParsingStatus() {
    UUID ticketId = UUID.randomUUID();
    Instant updatedAt = Instant.parse("2026-06-01T12:00:00Z");
    TicketSearchRow row =
        new TicketSearchRow(
            ticketId, PROJECT, "ENG", 42, "Auth flow", "IN_PROGRESS", updatedAt, "snip [[Auth]]", 0.75);
    when(repository.searchProject(any(), any(), anyInt(), anyInt())).thenReturn(List.of(row));
    when(repository.countProject(any(), any())).thenReturn(1L);

    PagedResponse<TicketSearchResult> result = service.searchProject(PROJECT, "auth", 0, 25);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items()).hasSize(1);
    TicketSearchResult item = result.items().get(0);
    assertThat(item.ticketKey()).isEqualTo("ENG-42"); // projectKey + "-" + number
    assertThat(item.ticketId()).isEqualTo(ticketId);
    assertThat(item.status()).isEqualTo(TicketStatus.IN_PROGRESS);
    assertThat(item.projectKey()).isEqualTo("ENG");
    assertThat(item.snippet()).isEqualTo("snip [[Auth]]");
    assertThat(item.rank()).isEqualTo(0.75);
  }
}
