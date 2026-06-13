package io.ngss.atlas.attachment;

import static io.restassured.RestAssured.given;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.ngss.atlas.Application;
import io.ngss.atlas.BaseIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Shared harness for the T-025 attachment ITs. Two guarded SINGLETON containers
 * (Postgres + MinIO) following the post-T-024 pattern (testcontainers_singleton_shared_base):
 * started ONCE in a Docker-guarded static block, NOT JUnit-managed @Containers, so the
 * cached Spring context shared across subclasses never points at a stopped container.
 * Both OBJECT_STORAGE_ENDPOINT and OBJECT_STORAGE_PUBLIC_ENDPOINT map to the same MinIO
 * URL in tests (one reachable host). Self-skips without Docker.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"BCRYPT_COST=12", "spring.jpa.hibernate.ddl-auto=validate"})
abstract class AttachmentITBase extends BaseIT {

  static final String SECRET = "attachment-it-secret-min-32-characters-long!!";
  static final String BUCKET = "atlas-test";
  static final String ACCESS_KEY = "minioadmin";
  static final String SECRET_KEY = "minioadmin123";

  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("atlas")
          .withUsername("atlas")
          .withPassword("atlas");

  @SuppressWarnings("resource")
  static final MinIOContainer MINIO =
      new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
          .withUserName(ACCESS_KEY)
          .withPassword(SECRET_KEY);

  /** Throwaway client for bucket bootstrap + direct PUT/HEAD in tests (NOT the app bean). */
  static S3Client testS3;

  static {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
      MINIO.start();
      testS3 =
          S3Client.builder()
              .endpointOverride(URI.create(MINIO.getS3URL()))
              .region(Region.US_EAST_1)
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
              .forcePathStyle(true)
              .httpClient(UrlConnectionHttpClient.create())
              .build();
      try {
        testS3.createBucket(b -> b.bucket(BUCKET));
      } catch (S3Exception e) {
        // BucketAlreadyOwnedByYou / already exists — fine across JVM reuse.
      }
    }
  }

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
    // Both endpoints → the same mapped MinIO URL (one reachable host in tests).
    registry.add("OBJECT_STORAGE_ENDPOINT", MINIO::getS3URL);
    registry.add("OBJECT_STORAGE_PUBLIC_ENDPOINT", MINIO::getS3URL);
    registry.add("OBJECT_STORAGE_REGION", () -> "us-east-1");
    registry.add("OBJECT_STORAGE_BUCKET", () -> BUCKET);
    registry.add("OBJECT_STORAGE_ACCESS_KEY", () -> ACCESS_KEY);
    registry.add("OBJECT_STORAGE_SECRET_KEY", () -> SECRET_KEY);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void baseSetUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    BaseIT.cleanDatabase(jdbc);
  }

  @AfterEach
  void baseReset() {
    RestAssured.reset();
  }

  // ───────────────────────── auth/project/ticket helpers ─────────────────────────

  UUID register(String email, String displayName) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"email\":\""
                    + email
                    + "\",\"password\":\"Password123!\",\"displayName\":\""
                    + displayName
                    + "\"}")
            .post("/api/auth/register")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    return UUID.fromString(id);
  }

  static String sign(UUID subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject.toString())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String createProject(String token, String key, String name) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
        .post("/api/projects")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  void addMember(String adminToken, String projectId, String email) {
    given()
        .header("Authorization", "Bearer " + adminToken)
        .contentType(ContentType.JSON)
        .body("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + projectId + "/members")
        .then()
        .statusCode(201);
  }

  String createTicket(String token, String projectId, String title) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body("{\"title\":\"" + title + "\"}")
        .post("/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  // ───────────────────────── attachment HTTP helpers ─────────────────────────

  Response initUpload(
      String token, String ticketId, String filename, String contentType, long sizeBytes) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(
            "{\"filename\":\""
                + filename
                + "\",\"contentType\":\""
                + contentType
                + "\",\"sizeBytes\":"
                + sizeBytes
                + "}")
        .post("/api/tickets/" + ticketId + "/attachments/init");
  }

  Response finalizeUpload(String token, String attachmentId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .post("/api/attachments/" + attachmentId + "/finalize");
  }

  Response listAttachments(String token, String ticketId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .get("/api/tickets/" + ticketId + "/attachments");
  }

  Response downloadUrl(String token, String attachmentId, String query) {
    return given()
        .header("Authorization", "Bearer " + token)
        .get("/api/attachments/" + attachmentId + "/download-url" + query);
  }

  Response deleteAttachment(String token, String attachmentId) {
    return given()
        .header("Authorization", "Bearer " + token)
        .delete("/api/attachments/" + attachmentId);
  }

  // ───────────────────────── S3 helpers ─────────────────────────

  /** HTTP PUT to a presigned URL with the signed Content-Type. Returns the status code. */
  static int httpPut(String presignedUrl, byte[] body, String contentType) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(presignedUrl))
              .header("Content-Type", contentType)
              .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      return HttpClient.newHttpClient()
          .send(req, HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Direct PUT (bypassing the signed URL) — for forcing a content-type mismatch. */
  static void putObjectDirect(String objectKey, byte[] body, String contentType) {
    testS3.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(objectKey).contentType(contentType).build(),
        RequestBody.fromBytes(body));
  }

  static boolean objectExists(String objectKey) {
    try {
      testS3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build());
      return true;
    } catch (S3Exception e) {
      return false;
    }
  }

  // ───────────────────────── JDBC assertion helpers ─────────────────────────

  String attachmentStatus(String attachmentId) {
    return jdbc.queryForObject(
        "SELECT status FROM attachments WHERE id=?::uuid", String.class, attachmentId);
  }

  String objectKey(String attachmentId) {
    return jdbc.queryForObject(
        "SELECT object_key FROM attachments WHERE id=?::uuid", String.class, attachmentId);
  }

  String thumbnailKey(String attachmentId) {
    return jdbc.queryForObject(
        "SELECT thumbnail_object_key FROM attachments WHERE id=?::uuid", String.class, attachmentId);
  }

  int countActivity(String ticketId, String eventType) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM activity_events WHERE ticket_id=?::uuid AND event_type=?",
        Integer.class,
        ticketId,
        eventType);
  }
}
