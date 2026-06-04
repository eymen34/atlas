package io.ngss.atlas.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Project}. All finders are scoped to live
 * (non-soft-deleted) rows via the {@code AndDeletedAtIsNull} suffix, mirroring
 * the soft-delete model in V4. Queries are derived — no {@code @Query} strings.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

  Optional<Project> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Project> findByKeyAndDeletedAtIsNull(String key);

  List<Project> findByCreatedByAndDeletedAtIsNull(UUID createdBy);

  boolean existsByKeyAndDeletedAtIsNull(String key);
}
