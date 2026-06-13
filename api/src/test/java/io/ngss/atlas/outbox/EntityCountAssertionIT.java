package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * AC-8 / entity_appcds_hard_rule: T-029 adds the {@code outbox} table as NATIVE-SQL-ONLY (no JPA
 * {@code @Entity}), so the entity count MUST stay 17. Pure reflection scan — no Docker, no Spring
 * context — so it runs locally AND in CI as a fast guard against an accidental {@code @Entity}.
 */
class EntityCountAssertionIT {

  @Test
  void domainEntityCountStaysSeventeen() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    int entityCount = scanner.findCandidateComponents("io.ngss.atlas.domain").size();
    assertThat(entityCount).isEqualTo(17);
  }
}
