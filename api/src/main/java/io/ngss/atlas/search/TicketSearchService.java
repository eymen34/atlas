package io.ngss.atlas.search;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.search.dto.TicketSearchResult;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-text search service (T-028). Clamps paging, runs the native search + count, and
 * maps rows to {@link TicketSearchResult} (assembling {@code ticketKey} = projectKey-number
 * and parsing the status). Returns a {@link PagedResponse} built DIRECTLY (the native repo
 * returns a List, not a Spring Page — paged_response_pattern). {@code @Transactional(readOnly)}
 * because open-in-view is off — the native query needs an active transaction.
 */
@Service
public class TicketSearchService {

  private static final int MAX_SIZE = 100;

  private final TicketSearchRepository repository;

  public TicketSearchService(TicketSearchRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public PagedResponse<TicketSearchResult> searchProject(
      UUID projectId, String q, int page, int size) {
    int clampedSize = clampSize(size);
    int clampedPage = Math.max(0, page);
    int offset = clampedPage * clampedSize;
    List<TicketSearchResult> items =
        repository.searchProject(projectId, q, clampedSize, offset).stream()
            .map(TicketSearchService::toResult)
            .toList();
    long total = repository.countProject(projectId, q);
    return new PagedResponse<>(items, clampedPage, clampedSize, total);
  }

  @Transactional(readOnly = true)
  public PagedResponse<TicketSearchResult> searchGlobal(UUID callerId, String q, int page, int size) {
    int clampedSize = clampSize(size);
    int clampedPage = Math.max(0, page);
    int offset = clampedPage * clampedSize;
    List<TicketSearchResult> items =
        repository.searchGlobal(callerId, q, clampedSize, offset).stream()
            .map(TicketSearchService::toResult)
            .toList();
    long total = repository.countGlobal(callerId, q);
    return new PagedResponse<>(items, clampedPage, clampedSize, total);
  }

  private static int clampSize(int size) {
    if (size < 1) {
      return 1;
    }
    return Math.min(size, MAX_SIZE);
  }

  private static TicketSearchResult toResult(TicketSearchRow r) {
    return new TicketSearchResult(
        r.projectKey() + "-" + r.number(),
        r.id(),
        r.title(),
        TicketStatus.valueOf(r.status()),
        r.projectKey(),
        r.projectId(),
        r.snippet(),
        r.updatedAt(),
        r.rank());
  }
}
