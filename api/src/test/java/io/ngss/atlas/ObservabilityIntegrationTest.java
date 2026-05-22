package io.ngss.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    // app.database.* — consumed by the custom DataSourceConfig @Bean
    // (DatabaseProperties @ConfigurationProperties(prefix="app.database")).
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
    // spring.datasource.* — defensive: registered so any code path that
    // reads Spring Boot's standard DataSource properties sees consistent
    // values even though the custom @Bean DataSource short-circuits
    // DataSourceAutoConfiguration in this setup.
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @LocalServerPort int port;

  @Autowired ObjectMapper mapper;

  @Test
  @Order(1)
  void healthReturnsUp() {
    RestAssured.given()
        .port(port)
        .when()
        .get("/health")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("status", equalTo("UP"))
        .time(org.hamcrest.Matchers.lessThan(2_000L), TimeUnit.MILLISECONDS);
  }

  @Test
  @Order(2)
  void readyReturnsReadyWhenDatabaseIsUp() {
    RestAssured.given()
        .port(port)
        .when()
        .get("/ready")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("status", equalTo("READY"));
  }

  @Test
  @Order(3)
  void prometheusExposesRequiredMetrics() {
    RestAssured.given().port(port).get("/health");
    RestAssured.given().port(port).get("/ready");

    String body =
        RestAssured.given()
            .port(port)
            .when()
            .get("/actuator/prometheus")
            .then()
            .statusCode(200)
            .contentType(startsWith("text/plain"))
            .extract()
            .asString();

    assertThat(body).contains("hikaricp_connections_active");
    assertThat(body).contains("http_server_requests_seconds");
    boolean hasAnyJvmMetric =
        body.contains("jvm_memory_used_bytes")
            || body.contains("jvm_gc_pause_seconds")
            || body.contains("jvm_threads_live_threads");
    assertThat(hasAnyJvmMetric).as("at least one JVM metric must be exposed").isTrue();
  }

  @Test
  @Order(4)
  void forbiddenActuatorEndpointsReturn404() {
    for (String suffix : List.of("env", "beans", "mappings", "configprops", "heapdump")) {
      int status =
          RestAssured.given()
              .port(port)
              .when()
              .get("/actuator/" + suffix)
              .then()
              .extract()
              .statusCode();
      assertThat(status).as("/actuator/%s must not be exposed", suffix).isEqualTo(404);
    }
  }

  @Test
  @Order(5)
  void actuatorDiscoveryListsOnlyAllowedEndpoints() throws Exception {
    String body =
        RestAssured.given().port(port).when().get("/actuator").then().statusCode(200).extract().asString();
    JsonNode links = mapper.readTree(body).get("_links");
    assertThat(links.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("self", "health", "info", "prometheus");
  }

  @Test
  @Order(6)
  void actuatorHealthReturns200WithoutBodyAssertion() {
    RestAssured.given().port(port).when().get("/actuator/health").then().statusCode(200);
  }

  @Test
  @Order(7)
  void concurrentReadyCallsAllReturnReady() throws Exception {
    ExecutorService exec = Executors.newFixedThreadPool(10);
    try {
      CompletableFuture<?>[] futures =
          java.util.stream.IntStream.range(0, 10)
              .mapToObj(
                  i ->
                      CompletableFuture.runAsync(
                          () ->
                              RestAssured.given()
                                  .port(port)
                                  .when()
                                  .get("/ready")
                                  .then()
                                  .statusCode(200)
                                  .body("status", equalTo("READY")),
                          exec))
              .toArray(CompletableFuture[]::new);
      CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
    } finally {
      exec.shutdownNow();
    }
  }

  @Test
  @Order(8)
  void requestIdHeaderIsEchoedOnHealth() {
    String id = "smoke-request-id-abc-123";
    String echoed =
        RestAssured.given()
            .port(port)
            .header("X-Request-Id", id)
            .when()
            .get("/health")
            .then()
            .statusCode(200)
            .extract()
            .header("X-Request-Id");
    assertThat(echoed).isEqualTo(id);
  }

  @Test
  @Order(9)
  void readyReturns503AfterPostgresStops() {
    POSTGRES.stop();
    try {
      long start = System.currentTimeMillis();
      RestAssured.given()
          .port(port)
          .when()
          .get("/ready")
          .then()
          .statusCode(503)
          .contentType("application/json")
          .body("status", equalTo("NOT_READY"));
      long elapsed = System.currentTimeMillis() - start;
      assertThat(elapsed)
          .as("ready must return within 2000ms even with DB down")
          .isLessThan(2_000L);
    } finally {
      POSTGRES.start();
    }
  }
}
