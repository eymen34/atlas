package io.ngss.atlas.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Generic offset-pagination envelope (T-018). Wraps a page of already-mapped items
 * with the page metadata clients need to render pagination controls.
 *
 * @param items the items on this page (already mapped to the response type)
 * @param page the zero-based page index
 * @param size the requested (clamped) page size
 * @param total the total number of matching rows across all pages
 */
public record PagedResponse<T>(List<T> items, int page, int size, long total) {

  /**
   * Builds a {@link PagedResponse} from a Spring Data {@link Page}, mapping each
   * element to the response type. {@code page.getSize()} is the requested page size
   * (already clamped by the caller), {@code page.getNumber()} the zero-based index,
   * {@code page.getTotalElements()} the grand total.
   */
  public static <U, T> PagedResponse<T> from(Page<U> page, Function<U, T> mapper) {
    return new PagedResponse<>(
        page.getContent().stream().map(mapper).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }
}
