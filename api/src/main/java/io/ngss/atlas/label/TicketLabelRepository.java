package io.ngss.atlas.label;

import io.ngss.atlas.domain.TicketLabel;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link TicketLabel} (T-018).
 *
 * <p>{@link #findByTicketIdIn} is the SINGLE batch query that loads labels for a
 * whole page of tickets (no N+1). The two {@code @Modifying} bulk deletes are JPQL
 * DML (one DELETE statement each, no entity load): {@code deleteByTicketId} backs
 * the idempotent PUT replace, {@code deleteByLabelId} backs label deletion (called
 * BEFORE deleting the label row so the FK is never violated).
 */
public interface TicketLabelRepository extends JpaRepository<TicketLabel, UUID> {

  List<TicketLabel> findByTicketId(UUID ticketId);

  List<TicketLabel> findByTicketIdIn(Collection<UUID> ticketIds);

  @Modifying
  @Query("DELETE FROM TicketLabel t WHERE t.ticketId = :ticketId")
  void deleteByTicketId(@Param("ticketId") UUID ticketId);

  @Modifying
  @Query("DELETE FROM TicketLabel t WHERE t.labelId = :labelId")
  void deleteByLabelId(@Param("labelId") UUID labelId);
}
