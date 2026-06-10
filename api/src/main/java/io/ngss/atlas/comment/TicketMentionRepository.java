package io.ngss.atlas.comment;

import io.ngss.atlas.domain.TicketMention;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link TicketMention} (T-022). */
public interface TicketMentionRepository extends JpaRepository<TicketMention, UUID> {

  /** Removes all description-mentions for a ticket (re-diffed on description change). */
  @Modifying
  @Query("DELETE FROM TicketMention tm WHERE tm.ticketId = :ticketId")
  void deleteByTicketId(@Param("ticketId") UUID ticketId);
}
