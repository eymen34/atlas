package io.ngss.atlas.ticket;

import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketLabel;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Criteria {@link Specification}s for the dynamic ticket list (T-018).
 *
 * <p>Each helper returns a {@code Specification<Ticket>} whose {@code toPredicate}
 * yields {@code null} when its filter is absent (so it is skipped). {@link #build}
 * composes them with a single {@code cb.and} over the non-null predicates — this
 * deliberately avoids {@code Specification.where/.and/.allOf}, whose static
 * composition API shifted across Spring Data JPA versions; the {@code toPredicate}
 * functional contract and {@code cb.and} are stable.
 *
 * <p>Status and priority are OR-within-field (multi-valued {@code IN}); the label
 * filter is AND-across-labels (relational division: a ticket must carry EVERY
 * requested label, via {@code GROUP BY … HAVING COUNT(DISTINCT label_id) = n}).
 */
public final class TicketSpecifications {

  private TicketSpecifications() {}

  public static Specification<Ticket> forProject(UUID projectId) {
    return (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
  }

  public static Specification<Ticket> notDeleted() {
    return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
  }

  public static Specification<Ticket> statusIn(List<TicketStatus> statuses) {
    return (root, query, cb) ->
        (statuses == null || statuses.isEmpty()) ? null : root.get("status").in(statuses);
  }

  public static Specification<Ticket> priorityIn(List<TicketPriority> priorities) {
    return (root, query, cb) ->
        (priorities == null || priorities.isEmpty()) ? null : root.get("priority").in(priorities);
  }

  /**
   * {@code null}/blank → no filter; the exact lowercase literal {@code "unassigned"}
   * → {@code assignee_id IS NULL}; otherwise a UUID equality (a non-UUID value →
   * {@link InvalidQueryParamException} → 400).
   */
  public static Specification<Ticket> assigneeFilter(String raw) {
    return (root, query, cb) -> {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      if (raw.equals("unassigned")) {
        return cb.isNull(root.get("assigneeId"));
      }
      UUID assignee;
      try {
        assignee = UUID.fromString(raw);
      } catch (IllegalArgumentException notAUuid) {
        throw new InvalidQueryParamException("Invalid value for parameter: assigneeId");
      }
      return cb.equal(root.get("assigneeId"), assignee);
    };
  }

  /**
   * AND-semantics: matches tickets that carry ALL of {@code labelIds}. Correlated
   * subquery over {@code ticket_labels} grouped by ticket, kept only when the count
   * of DISTINCT matching labels equals the number requested (relational division —
   * NOT a plain {@code IN}, which would be OR-semantics).
   */
  public static Specification<Ticket> hasAllLabels(List<UUID> labelIds) {
    return (root, query, cb) -> {
      if (labelIds == null || labelIds.isEmpty()) {
        return null;
      }
      Subquery<UUID> sq = query.subquery(UUID.class);
      Root<TicketLabel> tl = sq.from(TicketLabel.class);
      sq.select(tl.get("ticketId"))
          .where(tl.get("labelId").in(labelIds))
          .groupBy(tl.get("ticketId"))
          .having(cb.equal(cb.countDistinct(tl.get("labelId")), (long) labelIds.size()));
      return root.get("id").in(sq);
    };
  }

  /** Combines all filters with AND over the non-null predicates. */
  public static Specification<Ticket> build(
      UUID projectId,
      List<TicketStatus> statuses,
      List<TicketPriority> priorities,
      String assigneeId,
      List<UUID> labelIds) {
    List<Specification<Ticket>> parts =
        List.of(
            forProject(projectId),
            notDeleted(),
            statusIn(statuses),
            priorityIn(priorities),
            assigneeFilter(assigneeId),
            hasAllLabels(labelIds));
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      for (Specification<Ticket> part : parts) {
        Predicate predicate = part.toPredicate(root, query, cb);
        if (predicate != null) {
          predicates.add(predicate);
        }
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
