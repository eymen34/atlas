package io.ngss.atlas.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Native full-text search over {@code tickets} (T-028). EntityManager native SQL
 * (counter_returning_pattern) — there is no entity for the {@code search_doc} tsvector
 * (it is unmapped). User input is ALWAYS bound via {@code plainto_tsquery('english', :q)}
 * — NEVER string-concatenated — so a query is injection-safe regardless of its content.
 * The only string substitution is the hardcoded {@code ${SCOPE}} predicate (project vs
 * global), never user data. The {@code ts_headline} document expression is byte-identical
 * to V14's generated-column expression so highlights match what was indexed.
 */
@Repository
public class TicketSearchRepository {

  private static final String CORE_SELECT =
      "SELECT t.id, t.project_id, p.key, t.number, t.title, t.status, t.updated_at, "
          + "ts_headline('english', coalesce(t.title, '') || ' ' || coalesce(t.description, ''), "
          + "  plainto_tsquery('english', :q), "
          + "  'StartSel=[[, StopSel=]], MaxFragments=2, MaxWords=18, MinWords=5') AS snippet, "
          + "ts_rank_cd(t.search_doc, plainto_tsquery('english', :q)) AS rank "
          + "FROM tickets t JOIN projects p ON p.id = t.project_id "
          + "WHERE t.deleted_at IS NULL "
          + "  AND t.search_doc @@ plainto_tsquery('english', :q) "
          + "  AND ${SCOPE} "
          + "ORDER BY rank DESC, t.updated_at DESC "
          + "LIMIT :size OFFSET :offset";

  private static final String CORE_COUNT =
      "SELECT count(*) FROM tickets t "
          + "WHERE t.deleted_at IS NULL "
          + "  AND t.search_doc @@ plainto_tsquery('english', :q) "
          + "  AND ${SCOPE}";

  private static final String SCOPE_PROJECT = "t.project_id = :projectId";
  // Authorization IN SQL — never a Java-side post-filter (AC3).
  private static final String SCOPE_GLOBAL =
      "t.project_id IN (SELECT pm.project_id FROM project_members pm WHERE pm.user_id = :callerId)";

  @PersistenceContext private EntityManager em;

  public List<TicketSearchRow> searchProject(UUID projectId, String q, int size, int offset) {
    return rows(
        em.createNativeQuery(CORE_SELECT.replace("${SCOPE}", SCOPE_PROJECT))
            .setParameter("q", q)
            .setParameter("projectId", projectId)
            .setParameter("size", size)
            .setParameter("offset", offset));
  }

  public long countProject(UUID projectId, String q) {
    return ((Number)
            em.createNativeQuery(CORE_COUNT.replace("${SCOPE}", SCOPE_PROJECT))
                .setParameter("q", q)
                .setParameter("projectId", projectId)
                .getSingleResult())
        .longValue();
  }

  public List<TicketSearchRow> searchGlobal(UUID callerId, String q, int size, int offset) {
    return rows(
        em.createNativeQuery(CORE_SELECT.replace("${SCOPE}", SCOPE_GLOBAL))
            .setParameter("q", q)
            .setParameter("callerId", callerId)
            .setParameter("size", size)
            .setParameter("offset", offset));
  }

  public long countGlobal(UUID callerId, String q) {
    return ((Number)
            em.createNativeQuery(CORE_COUNT.replace("${SCOPE}", SCOPE_GLOBAL))
                .setParameter("q", q)
                .setParameter("callerId", callerId)
                .getSingleResult())
        .longValue();
  }

  @SuppressWarnings("unchecked")
  private List<TicketSearchRow> rows(Query query) {
    List<Object[]> raw = query.getResultList();
    return raw.stream().map(TicketSearchRepository::map).toList();
  }

  private static TicketSearchRow map(Object[] r) {
    return new TicketSearchRow(
        (UUID) r[0],
        (UUID) r[1],
        (String) r[2],
        ((Number) r[3]).intValue(),
        (String) r[4],
        (String) r[5],
        toInstant(r[6]),
        (String) r[7],
        ((Number) r[8]).doubleValue());
  }

  /** timestamptz arrives as one of these depending on driver/Hibernate version. */
  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) {
      return i;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.toInstant();
    }
    if (value instanceof Timestamp ts) {
      return ts.toInstant();
    }
    throw new IllegalStateException("unexpected updated_at type: " + value.getClass());
  }
}
