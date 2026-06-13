package io.ngss.atlas.link;

import io.ngss.atlas.domain.LinkRelation;
import io.ngss.atlas.domain.TicketLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

/** Spring Data repository for {@link TicketLink} (T-026). */
public interface TicketLinkRepository extends JpaRepository<TicketLink, UUID> {

  /** Any relation row in the given direction — the per-pair conflict pre-check (D4). */
  boolean existsByFromTicketIdAndToTicketId(UUID fromTicketId, UUID toTicketId);

  /** A ticket's outgoing links, newest first (the from-side rows shown on its detail page). */
  List<TicketLink> findByFromTicketIdOrderByCreatedAtDesc(UUID fromTicketId);

  /** Deletes the single row for an exact (from, to, relation) tuple (uq_link). */
  @Modifying
  void deleteByFromTicketIdAndToTicketIdAndRelation(
      UUID fromTicketId, UUID toTicketId, LinkRelation relation);
}
