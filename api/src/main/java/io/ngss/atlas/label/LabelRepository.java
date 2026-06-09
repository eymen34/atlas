package io.ngss.atlas.label;

import io.ngss.atlas.domain.Label;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Label} (T-018).
 *
 * <p>{@link #findByProjectIdAndNameIgnoreCase} is the create pre-check; its
 * {@code IgnoreCase} derivation issues {@code WHERE lower(name) = lower(?)}, which
 * lines up with the V7 functional unique index {@code (project_id, lower(name))}.
 */
public interface LabelRepository extends JpaRepository<Label, UUID> {

  List<Label> findByProjectIdOrderByNameAsc(UUID projectId);

  Optional<Label> findByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
