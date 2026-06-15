package io.ngss.atlas.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-3 / QG-10 (T-048): the CORS config allows credentials (so the browser sends the atlas_refresh
 * cookie cross-origin) with an EXPLICIT, non-wildcard origin list — {@code allowCredentials=true} is
 * incompatible with {@code "*"}. A guard on the existing fact: SecurityConfig is unchanged by this
 * ticket; if the origin list ever drifts to a wildcard, the cookie cross-origin contract silently
 * breaks.
 */
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class CorsConfigIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("JWT_SECRET", () -> "corsconfigit-secret-min-32-characters-long-ok");
  }

  @Autowired CorsConfigurationSource corsConfigurationSource;

  @Test
  void corsAllowsCredentialsWithExplicitNonWildcardOrigins() {
    assertThat(corsConfigurationSource).isInstanceOf(UrlBasedCorsConfigurationSource.class);
    CorsConfiguration cfg =
        ((UrlBasedCorsConfigurationSource) corsConfigurationSource)
            .getCorsConfigurations()
            .get("/**");

    assertThat(cfg).as("/** CORS config registered").isNotNull();
    assertThat(cfg.getAllowCredentials()).as("allowCredentials=true").isTrue();
    assertThat(cfg.getAllowedOrigins()).as("at least one explicit origin").isNotEmpty();
    assertThat(cfg.getAllowedOrigins()).as("no wildcard origin").doesNotContain("*");
  }
}
