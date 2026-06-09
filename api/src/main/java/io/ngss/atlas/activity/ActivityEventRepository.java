package io.ngss.atlas.activity;

import io.ngss.atlas.domain.ActivityEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ActivityEvent} (T-019). The newest-first paged
 * finder backs GET /api/tickets/{id}/activity; it returns a Spring {@link Page}
 * (data + count) which the controller maps to the API's {@code PagedResponse}.
 */
public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {

  Page<ActivityEvent> findByTicketIdOrderByCreatedAtDesc(UUID ticketId, Pageable pageable);

  long countByTicketId(UUID ticketId);
}
