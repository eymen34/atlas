package io.ngss.atlas.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/**
 * Spring Data repository for {@link ProjectTicketCounter} (T-017). Standard CRUD
 * (used to seed the {@code next_number = 1} row in {@code ProjectService.create})
 * plus the {@link ProjectTicketCounterRepositoryCustom} fragment that atomically
 * claims the next ticket number.
 */
public interface ProjectTicketCounterRepository
    extends JpaRepository<ProjectTicketCounter, UUID>, ProjectTicketCounterRepositoryCustom {}
