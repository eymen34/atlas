package io.ngss.atlas.project;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.UUID;
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
 * Race-safety of the last-admin guard and the duplicate-member guard (T-015):
 * two simultaneous demote/remove attempts must leave ≥1 admin, and two
 * simultaneous adds of the same user must yield exactly one 201 + one 409.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class LastAdminRaceIT {

  private static final String SECRET = "raceit-secret-min-32-characters-long-okay-y!";

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

  private UUID admin1;
  private UUID admin2;
  private UUID target;
  private String admin1Token;
  private String admin2Token;
  private String projectId;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    jdbc.update("DELETE FROM project_members");
    jdbc.update("DELETE FROM projects");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");

    admin1 = register("a1@example.com", "A1");
    admin2 = register("a2@example.com", "A2");
    target = register("target@example.com", "Target");
    admin1Token = sign(admin1);
    admin2Token = sign(admin2);

    projectId =
        given()
            .header("Authorization", "Bearer " + admin1Token)
            .contentType(ContentType.JSON)
            .body("{\"key\":\"RACEP\",\"name\":\"Race\"}")
            .when()
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    // Promote admin2 to ADMIN so the project has exactly two admins.
    given()
        .header("Authorization", "Bearer " + admin1Token)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"a2@example.com\",\"role\":\"ADMIN\"}")
        .when()
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
  }

  @AfterEach
  void reset() {
    RestAssured.reset();
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

  /** Fires two suppliers simultaneously and returns their two HTTP status codes. */
  private int[] runConcurrently(Supplier<Integer> a, Supplier<Integer> b)
      throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger ra = new AtomicInteger();
    AtomicInteger rb = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch done = new CountDownLatch(2);
    pool.submit(
        () -> {
          await(start);
          ra.set(a.get());
          done.countDown();
        });
    pool.submit(
        () -> {
          await(start);
          rb.set(b.get());
          done.countDown();
        });
    start.countDown();
    assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();
    return new int[] {ra.get(), rb.get()};
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private int adminCount() {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id=?::uuid AND role='ADMIN'",
            Integer.class,
            projectId);
    return n == null ? 0 : n;
  }

  private int demoteSelf(String token, UUID self) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .when()
        .patch("/api/projects/" + projectId + "/members/" + self)
        .then()
        .extract()
        .statusCode();
  }

  private int removeSelf(String token, UUID self) {
    return given()
        .header("Authorization", "Bearer " + token)
        .when()
        .delete("/api/projects/" + projectId + "/members/" + self)
        .then()
        .extract()
        .statusCode();
  }

  @Test
  void concurrentDemotion_leavesAtLeastOneAdmin() throws InterruptedException {
    int[] codes =
        runConcurrently(
            () -> demoteSelf(admin1Token, admin1), () -> demoteSelf(admin2Token, admin2));
    assertThat(List.of(codes[0], codes[1])).containsExactlyInAnyOrder(200, 400);
    assertThat(adminCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void concurrentRemoval_leavesAtLeastOneAdmin() throws InterruptedException {
    int[] codes =
        runConcurrently(
            () -> removeSelf(admin1Token, admin1), () -> removeSelf(admin2Token, admin2));
    assertThat(List.of(codes[0], codes[1])).containsExactlyInAnyOrder(204, 400);
    assertThat(adminCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void concurrentAddSameUser_yieldsOneCreatedOneConflict() throws InterruptedException {
    Supplier<Integer> add =
        () ->
            given()
                .header("Authorization", "Bearer " + admin1Token)
                .contentType(ContentType.JSON)
                .body("{\"email\":\"target@example.com\",\"role\":\"MEMBER\"}")
                .when()
                .post("/api/projects/" + projectId + "/members")
                .then()
                .extract()
                .statusCode();
    int[] codes = runConcurrently(add, add);
    assertThat(List.of(codes[0], codes[1])).containsExactlyInAnyOrder(201, 409);
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id=?::uuid AND user_id=?::uuid",
            Integer.class,
            projectId,
            target.toString());
    assertThat(rows).isEqualTo(1);
  }
}
