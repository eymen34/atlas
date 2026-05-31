package io.ngss.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts both halves of the hibernate_cold_start pair-rule are present in
 * application.yml. Removing either half re-introduces the
 * "Unable to determine Dialect without JDBC metadata" boot failure and breaks
 * the no-DB AppCDS warm-up performed in stage 3 of /Dockerfile.
 */
class ApplicationYmlPairRuleTest {

  @Test
  @SuppressWarnings("unchecked")
  void bothHalvesOfHibernateColdStartPairRuleArePresent() throws Exception {
    Map<String, Object> root;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("application.yml")) {
      assertThat(in).as("application.yml must be on the classpath").isNotNull();
      root = new Yaml().load(in);
    }

    Map<String, Object> spring = (Map<String, Object>) root.get("spring");
    Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");

    assertThat(jpa.get("database-platform"))
        .as(
            "PAIR-RULE half 1: spring.jpa.database-platform must be"
                + " org.hibernate.dialect.PostgreSQLDialect")
        .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");

    Map<String, Object> jpaProps = (Map<String, Object>) jpa.get("properties");
    Map<String, Object> hibernate = (Map<String, Object>) jpaProps.get("hibernate");
    Map<String, Object> boot = (Map<String, Object>) hibernate.get("boot");
    assertThat(boot.get("allow_jdbc_metadata_access"))
        .as(
            "PAIR-RULE half 2: spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access"
                + " must be false")
        .isEqualTo(false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void startupCheckDefaultEnabledIsTrue() throws Exception {
    Map<String, Object> root;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("application.yml")) {
      root = new Yaml().load(in);
    }
    Map<String, Object> app = (Map<String, Object>) root.get("app");
    Map<String, Object> database = (Map<String, Object>) app.get("database");
    Map<String, Object> startupCheck = (Map<String, Object>) database.get("startup-check");

    assertThat(startupCheck.get("enabled"))
        .as("app.database.startup-check.enabled must default to true")
        .isEqualTo(true);
  }
}
