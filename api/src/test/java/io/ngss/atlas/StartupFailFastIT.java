package io.ngss.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.zaxxer.hikari.HikariDataSource;
import io.restassured.RestAssured;
import java.time.Duration;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

class StartupFailFastIT {

  @Nested
  @SpringBootTest(
      classes = Application.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Testcontainers(disabledWithoutDocker = true)
  class HappyPathContextLoads {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
      registry.add("app.database.url", POSTGRES::getJdbcUrl);
      registry.add("app.database.username", POSTGRES::getUsername);
      registry.add("app.database.password", POSTGRES::getPassword);
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @LocalServerPort int port;

    @Autowired DataSource dataSource;

    @Test
    void actuatorHealthReturnsUp() {
      RestAssured.given()
          .port(port)
          .when()
          .get("/actuator/health")
          .then()
          .statusCode(200)
          .body("status", Matchers.equalTo("UP"));
    }

    @Test
    void hikariPoolConfiguredPerArchitectureDecisions() {
      assertThat(dataSource).isInstanceOf(HikariDataSource.class);
      HikariDataSource hikari = (HikariDataSource) dataSource;
      assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
      assertThat(hikari.getMinimumIdle()).isEqualTo(2);
      assertThat(hikari.getConnectionTimeout()).isEqualTo(5000L);
      String url = hikari.getJdbcUrl();
      assertThat(url).contains("prepareThreshold=0");
      assertThat(url).contains("preparedStatementCacheQueries=0");
      assertThat(url).contains("tcpKeepAlive=true");
    }
  }

  @Nested
  class FailFastOnUnreachableDatabase {

    @Test
    void unreachableHostFailsFastWithinDeadline() {
      assertTimeoutPreemptively(
          Duration.ofSeconds(15),
          () ->
              assertThatThrownBy(
                      () ->
                          SpringApplication.run(
                              Application.class,
                              "--server.port=0",
                              "--app.database.url=jdbc:postgresql://192.0.2.1:5432/atlas",
                              "--app.database.username=atlas",
                              "--app.database.password=ignored"))
                  .isInstanceOfAny(ApplicationContextException.class, RuntimeException.class)
                  .satisfies(
                      ex -> {
                        Throwable t = ex;
                        while (t != null) {
                          String m = t.getMessage();
                          if (m != null) {
                            assertThat(m).doesNotMatch(".*://[^/@\\s]+:[^/@\\s]+@.*");
                          }
                          t = t.getCause();
                        }
                      }));
    }
  }
}
