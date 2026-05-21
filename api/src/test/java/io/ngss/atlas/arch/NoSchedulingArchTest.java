package io.ngss.atlas.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

@AnalyzeClasses(
    packages = "io.ngss.atlas",
    importOptions = ImportOption.DoNotIncludeTests.class)
public class NoSchedulingArchTest {

  private static final String REASON =
      "HTTP polling only; see architecture_decisions.realtime — no WebSocket, no SSE, "
          + "no @Scheduled. Email and heavyweight work go through the outbox table, "
          + "drained by external cron.";

  @ArchTest
  static final ArchRule noScheduledAnnotation =
      ArchRuleDefinition.methods()
          .should()
          .notBeAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
          .because(REASON);

  @ArchTest
  static final ArchRule noEnableScheduling =
      ArchRuleDefinition.noClasses()
          .should()
          .beAnnotatedWith("org.springframework.scheduling.annotation.EnableScheduling")
          .because(REASON);

  @ArchTest
  static final ArchRule noScheduledExecutorService =
      ArchRuleDefinition.noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.util.concurrent.ScheduledExecutorService")
          .because(REASON);
}
