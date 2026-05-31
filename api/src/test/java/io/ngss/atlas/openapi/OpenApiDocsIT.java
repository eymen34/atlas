package io.ngss.atlas.openapi;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.Application;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class OpenApiDocsIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
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
    registry.add("JWT_SECRET", () -> "openapidocsit-test-secret-min-32-characters-long!");
  }

  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Autowired ObjectMapper objectMapper;

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
  void allFiveAuthPathsAndAllSixDtoSchemasArePresent() throws Exception {
    String body =
        given().when().get("/v3/api-docs").then().statusCode(200).extract().asString();
    JsonNode root = objectMapper.readTree(body);

    JsonNode paths = root.get("paths");
    assertThat(paths.has("/api/auth/register")).as("/api/auth/register").isTrue();
    assertThat(paths.has("/api/auth/login")).as("/api/auth/login").isTrue();
    assertThat(paths.has("/api/auth/refresh")).as("/api/auth/refresh").isTrue();
    assertThat(paths.has("/api/auth/logout")).as("/api/auth/logout").isTrue();
    assertThat(paths.has("/api/auth/me")).as("/api/auth/me").isTrue();

    // Method assertion (post-impl note): catches accidental method swaps.
    assertThat(paths.get("/api/auth/register").has("post")).isTrue();
    assertThat(paths.get("/api/auth/login").has("post")).isTrue();
    assertThat(paths.get("/api/auth/refresh").has("post")).isTrue();
    assertThat(paths.get("/api/auth/logout").has("post")).isTrue();
    assertThat(paths.get("/api/auth/me").has("get")).isTrue();

    JsonNode schemas = root.get("components").get("schemas");
    assertThat(schemas.has("RegisterRequest")).as("RegisterRequest schema").isTrue();
    assertThat(schemas.has("LoginRequest")).as("LoginRequest schema").isTrue();
    assertThat(schemas.has("RefreshRequest")).as("RefreshRequest schema").isTrue();
    assertThat(schemas.has("AuthResponse")).as("AuthResponse schema").isTrue();
    assertThat(schemas.has("UserProfileResponse")).as("UserProfileResponse schema").isTrue();
    assertThat(schemas.has("NotImplementedResponse")).as("NotImplementedResponse schema").isTrue();
  }

  @Test
  void loginSuccessResponseRefsAuthResponse() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());
    JsonNode ref =
        root.get("paths")
            .get("/api/auth/login")
            .get("post")
            .get("responses")
            .get("200")
            .get("content")
            .get("application/json")
            .get("schema")
            .get("$ref");
    assertThat(ref.asString()).contains("AuthResponse");
  }

  @Test
  void registerRequestBodyRefsRegisterRequest() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());
    JsonNode ref =
        root.get("paths")
            .get("/api/auth/register")
            .get("post")
            .get("requestBody")
            .get("content")
            .get("application/json")
            .get("schema")
            .get("$ref");
    assertThat(ref.asString()).contains("RegisterRequest");
  }

  @Test
  void meAndLogoutDeclareBearerAuthSecurityRequirement() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode meSecurity = root.get("paths").get("/api/auth/me").get("get").get("security");
    assertThat(meSecurity).as("/api/auth/me security").isNotNull();
    assertThat(meSecurity.isArray() && !meSecurity.isEmpty()).isTrue();
    assertThat(meSecurity.get(0).has("bearerAuth")).isTrue();

    JsonNode logoutSecurity =
        root.get("paths").get("/api/auth/logout").get("post").get("security");
    assertThat(logoutSecurity).as("/api/auth/logout security").isNotNull();
    assertThat(logoutSecurity.isArray() && !logoutSecurity.isEmpty()).isTrue();
    assertThat(logoutSecurity.get(0).has("bearerAuth")).isTrue();

    JsonNode securitySchemes = root.get("components").get("securitySchemes");
    assertThat(securitySchemes).isNotNull();
    assertThat(securitySchemes.has("bearerAuth")).isTrue();
    assertThat(securitySchemes.get("bearerAuth").get("scheme").asString()).isEqualTo("bearer");
    assertThat(securitySchemes.get("bearerAuth").get("bearerFormat").asString()).isEqualTo("JWT");
  }
}
