package io.ngss.atlas.security;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-4: the {@code @RequestScope} ProjectAccessGuard memoizes membership so a
 * single request issues exactly one {@code findByProjectIdAndUserId}, and a
 * second request starts with a fresh cache (proves request-scoping, not a
 * singleton cache). Uses {@code @MockitoSpyBean} (Boot 4 idiom).
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
class ProjectAccessGuardCacheIT {

  private static final String SECRET = "guardcacheit-secret-min-32-characters-long!!";

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

  @MockitoSpyBean ProjectMemberRepository memberRepositorySpy;

  private UUID adminId;
  private String adminToken;
  private UUID projectUuid;

  @BeforeEach
  void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    jdbc.update("DELETE FROM project_members");
    jdbc.update("DELETE FROM projects");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");

    adminId = register("admin@example.com");
    adminToken = sign(adminId);
    String id =
        given()
            .header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON)
            .body("{\"key\":\"CACHE\",\"name\":\"Cache\"}")
            .when()
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    projectUuid = UUID.fromString(id);
    // Clear interactions from setup (project creation, member seeding).
    reset(memberRepositorySpy);
  }

  @AfterEach
  void resetRa() {
    RestAssured.reset();
  }

  private UUID register(String email) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"email\":\"" + email + "\",\"password\":\"Password123!\",\"displayName\":\"A\"}")
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

  private void patchProject() {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"name\":\"Renamed\"}")
        .when()
        .patch("/api/projects/" + projectUuid)
        .then()
        .statusCode(200);
  }

  @Test
  void singleRequestTriggersExactlyOneLookup() {
    patchProject(); // requireAdmin → isMember + isAdmin, both via the per-request cache
    verify(memberRepositorySpy, times(1)).findByProjectIdAndUserId(projectUuid, adminId);
  }

  @Test
  void secondRequestStartsWithFreshCache() {
    patchProject();
    verify(memberRepositorySpy, times(1)).findByProjectIdAndUserId(projectUuid, adminId);
    reset(memberRepositorySpy);

    patchProject(); // a genuinely new HTTP request → new request scope → fresh lookup
    verify(memberRepositorySpy, times(1)).findByProjectIdAndUserId(projectUuid, adminId);
  }
}
