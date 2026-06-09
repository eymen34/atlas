package io.ngss.atlas.ticket;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-3: 50 simultaneous POSTs to the same project must yield 50 distinct ticket
 * numbers with no duplicates. The native UPDATE ... RETURNING counter takes a row
 * lock for its duration, so concurrent claimers serialize on the single counter
 * row; {@code UNIQUE(project_id, number)} is the backstop. A rollback could in
 * principle skip a number (acceptable, like a Postgres sequence), but with all 50
 * inserts succeeding the numbers must be exactly {1..50} and the counter must read
 * next_number = 51.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class TicketConcurrencyIT {

  private static final String SECRET = "ticketconc-secret-min-32-characters-long-ok!";
  private static final int N = 50;

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
    registry.add("JWT_SECRET", () -> SECRET);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private String token;
  private String projectId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);

    UUID owner = register("owner@example.com", "Owner");
    token = sign(owner);
    projectId =
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"key\":\"RACE\",\"name\":\"Race\"}")
            .when()
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
  }

  @Test
  void fiftyParallelCreates_yield50DistinctNumbers1To50() throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(N);
    ConcurrentLinkedQueue<Integer> numbers = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    ExecutorService pool = Executors.newFixedThreadPool(N);

    for (int i = 0; i < N; i++) {
      final int idx = i;
      pool.submit(
          () -> {
            await(start);
            try {
              var resp =
                  given()
                      .header("Authorization", "Bearer " + token)
                      .contentType(ContentType.JSON)
                      .body("{\"title\":\"concurrent-" + idx + "\"}")
                      .when()
                      .post("/api/projects/" + projectId + "/tickets");
              statuses.add(resp.statusCode());
              if (resp.statusCode() == 201) {
                numbers.add(resp.jsonPath().getInt("number"));
              }
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(120, TimeUnit.SECONDS)).as("all 50 requests completed").isTrue();
    pool.shutdownNow();

    // Every request succeeded.
    assertThat(statuses).hasSize(N);
    assertThat(statuses).allMatch(s -> s == 201);

    // Exactly {1..50}, no duplicates.
    Set<Integer> distinct = Set.copyOf(numbers);
    assertThat(numbers).as("no duplicate numbers issued").hasSize(N);
    assertThat(distinct).hasSize(N);
    Set<Integer> expected = IntStream.rangeClosed(1, N).boxed().collect(Collectors.toSet());
    assertThat(distinct).isEqualTo(expected);

    // DB agrees: 50 rows, and the counter advanced to 51.
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM tickets WHERE project_id=?::uuid", Integer.class, projectId);
    assertThat(rows).isEqualTo(N);
    Integer next =
        jdbc.queryForObject(
            "SELECT next_number FROM project_ticket_counters WHERE project_id=?::uuid",
            Integer.class,
            projectId);
    assertThat(next).isEqualTo(N + 1);

    // And every (project_id, number) is unique in the DB.
    List<Integer> dbNumbers =
        jdbc.queryForList(
            "SELECT number FROM tickets WHERE project_id=?::uuid ORDER BY number",
            Integer.class,
            projectId);
    assertThat(dbNumbers).isEqualTo(IntStream.rangeClosed(1, N).boxed().toList());
  }

  private UUID register(String email, String displayName) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"email\":\""
                    + email
                    + "\",\"password\":\"Password123!\",\"displayName\":\""
                    + displayName
                    + "\"}")
            .when()
            .post("/api/auth/register")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    return UUID.fromString(id);
  }

  private static String sign(UUID subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject.toString())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
