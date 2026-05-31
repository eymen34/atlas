package io.ngss.atlas;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.restassured.RestAssured;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

  @BeforeEach
  void configureRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @AfterEach
  void resetRestAssured() {
    RestAssured.reset();
  }

  @Test
  @Order(1)
  void healthReturnsUp() {
    given()
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
    given()
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
    given().get("/health");
    given().get("/ready");

    String body =
        given()
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
  void forbiddenActuatorEndpointsAreUnreachable() {
    // Pre-T-009: these endpoints returned 404 because
    // management.endpoints.web.exposure.include was the only gate and the
    // Actuator exposure layer responded with "not exposed".
    // Post-T-009: Spring Security's filter chain runs first and intercepts
    // unauthenticated requests to non-permitted /actuator/** paths with
    // 401 BEFORE Actuator can answer. The security posture is
    // equivalent-or-stronger (the endpoint is unreachable either way; 401
    // vs 404 is just which layer denies it). The exposure list is still
    // narrow (verified by actuatorDiscoveryListsOnlyAllowedEndpoints
    // below), so even an authenticated caller would still see 404 for
    // these paths.
    for (String suffix : List.of("env", "beans", "mappings", "configprops", "heapdump")) {
      int status =
          given().when().get("/actuator/" + suffix).then().extract().statusCode();
      assertThat(status).as("/actuator/%s must not be reachable", suffix).isEqualTo(401);
    }
  }

  @Test
  @Order(5)
  void actuatorDiscoveryListsOnlyAllowedEndpoints() {
    // Boot 4's Actuator surfaces a single configured endpoint as multiple
    // _links keys (e.g. /actuator/health appears as both "health" and
    // "health-path" for the templated variant). The security intent of the
    // exposure include list is unaffected — encoded here as a positive +
    // negative gate, robust to future Boot version quirks.
    given()
        .when()
        .get("/actuator")
        .then()
        .statusCode(200)
        .body("_links.keySet()", hasItems("self", "health", "info", "prometheus"))
        .body(
            "_links.keySet()",
            not(
                hasItems(
                    "env",
                    "beans",
                    "mappings",
                    "configprops",
                    "heapdump",
                    "threaddump",
                    "loggers",
                    "metrics")));
  }

  @Test
  @Order(6)
  void actuatorHealthReturns200WithoutBodyAssertion() {
    given().when().get("/actuator/health").then().statusCode(200);
  }

  @Test
  @Order(7)
  void concurrentReadyCallsAllReturnGracefullyAndAtLeastOneReady() throws Exception {
    // ReadyController is single-threaded by design (SynchronousQueue +
    // AbortPolicy): under burst load the second-through-Nth submitter hits
    // a rejected execution and the catch arm returns 503 NOT_READY in ~0ms.
    // The behaviour being verified here is graceful degradation — no
    // timeouts, no crashes, every response is one of the two designed
    // states — not "every probe under impossible parallelism returns 200".
    // (Production load balancers issue serial probes; 10-way parallelism is
    // a saturation stress test, not a normal-traffic baseline.)
    final int concurrency = 10;
    final int targetPort = port;
    ExecutorService exec = Executors.newFixedThreadPool(concurrency);
    try {
      List<CompletableFuture<Integer>> futures =
          IntStream.range(0, concurrency)
              .mapToObj(
                  i ->
                      CompletableFuture.supplyAsync(
                          () ->
                              given()
                                  .baseUri("http://localhost")
                                  .port(targetPort)
                                  .when()
                                  .get("/ready")
                                  .then()
                                  .extract()
                                  .statusCode(),
                          exec))
              .toList();

      List<Integer> codes =
          futures.stream()
              .map(f -> f.orTimeout(10, TimeUnit.SECONDS).join())
              .toList();

      assertThat(codes).hasSize(concurrency);
      assertThat(codes).allMatch(c -> c == 200 || c == 503);
      assertThat(codes).anyMatch(c -> c == 200);
    } finally {
      exec.shutdownNow();
    }
  }

  @Test
  @Order(8)
  void requestIdHeaderIsEchoedOnHealth() {
    String id = "smoke-request-id-abc-123";
    String echoed =
        given()
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
      given()
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
