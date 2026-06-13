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
    // T-012 replaced the 501 stubs: NotImplementedResponse is gone; LogoutRequest
    // and UserRegisteredResponse are now part of the contract.
    assertThat(schemas.has("UserRegisteredResponse")).as("UserRegisteredResponse schema").isTrue();
    assertThat(schemas.has("LogoutRequest")).as("LogoutRequest schema").isTrue();
  }

  @Test
  void t015MemberEndpointsSchemasAndForbiddenResponsesPresent() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode paths = root.get("paths");
    assertThat(paths.path("/api/projects/{id}/members").has("get")).as("members GET").isTrue();
    assertThat(paths.path("/api/projects/{id}/members").has("post")).as("members POST").isTrue();
    assertThat(paths.path("/api/projects/{id}/members/{userId}").has("patch"))
        .as("member PATCH")
        .isTrue();
    assertThat(paths.path("/api/projects/{id}/members/{userId}").has("delete"))
        .as("member DELETE")
        .isTrue();

    // 403 added to project mutations now that authorization is role-based.
    assertThat(paths.path("/api/projects/{id}").path("patch").path("responses").has("403")).isTrue();
    assertThat(paths.path("/api/projects/{id}").path("delete").path("responses").has("403"))
        .isTrue();

    JsonNode schemas = root.get("components").get("schemas");
    assertThat(schemas.has("MemberResponse")).as("MemberResponse schema").isTrue();
    assertThat(schemas.has("AddMemberRequest")).as("AddMemberRequest schema").isTrue();
    assertThat(schemas.has("UpdateMemberRoleRequest")).as("UpdateMemberRoleRequest schema").isTrue();

    // ProjectRole is emitted as a string enum (springdoc inlines it into the role property).
    JsonNode roleEnum =
        schemas.path("AddMemberRequest").path("properties").path("role").path("enum");
    assertThat(roleEnum.isArray()).as("role is a string enum").isTrue();
    assertThat(roleEnum.toString()).contains("MEMBER").contains("ADMIN");
  }

  @Test
  void t016ProjectResponseExposesCallerRoleAndMemberCount() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode props =
        root.path("components").path("schemas").path("ProjectResponse").path("properties");
    assertThat(props.isMissingNode()).as("ProjectResponse schema present").isFalse();

    // memberCount is an integer (long → integer in OpenAPI).
    assertThat(props.path("memberCount").path("type").asString())
        .as("memberCount type")
        .isEqualTo("integer");

    // callerRole is the ProjectRole enum. springdoc may EITHER inline the enum on
    // the property OR emit a $ref (possibly nested under allOf) to a named
    // ProjectRole schema. Accept both shapes so the next maintainer cannot
    // accidentally re-tighten this to one brittle path.
    JsonNode callerRole = props.path("callerRole");
    JsonNode inlinedEnum = callerRole.path("enum");
    boolean inlined =
        inlinedEnum.isArray()
            && inlinedEnum.toString().contains("ADMIN")
            && inlinedEnum.toString().contains("MEMBER");
    String directRef = callerRole.path("$ref").asString();
    String allOfRef = callerRole.path("allOf").path(0).path("$ref").asString();
    boolean referenced = directRef.contains("ProjectRole") || allOfRef.contains("ProjectRole");
    if (referenced) {
      JsonNode roleEnum =
          root.path("components").path("schemas").path("ProjectRole").path("enum");
      assertThat(roleEnum.toString()).as("ProjectRole named schema enum").contains("ADMIN").contains("MEMBER");
    }
    assertThat(inlined || referenced)
        .as("callerRole must be the ProjectRole enum (inlined or $ref'd), saw: %s", callerRole)
        .isTrue();
  }

  @Test
  void loginSuccessResponseRefsAuthResponse() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());
    // .path() returns MissingNode on absent keys (never null), so a mismatch
    // surfaces as an AssertJ failure with a clear message rather than an NPE.
    JsonNode ref =
        root.path("paths")
            .path("/api/auth/login")
            .path("post")
            .path("responses")
            .path("200")
            .path("content")
            .path("application/json")
            .path("schema")
            .path("$ref");
    assertThat(ref.isMissingNode() || ref.isNull())
        .as(
            "expected /api/auth/login POST 200 response to declare an application/json schema "
                + "$ref to AuthResponse; navigation result was missing/null")
        .isFalse();
    assertThat(ref.asString()).contains("AuthResponse");
  }

  @Test
  void registerRequestBodyRefsRegisterRequest() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());
    JsonNode ref =
        root.path("paths")
            .path("/api/auth/register")
            .path("post")
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("schema")
            .path("$ref");
    assertThat(ref.isMissingNode() || ref.isNull())
        .as(
            "expected /api/auth/register POST requestBody to declare an application/json schema "
                + "$ref to RegisterRequest; navigation result was missing/null")
        .isFalse();
    assertThat(ref.asString()).contains("RegisterRequest");
  }

  @Test
  void t022CommentEndpointsAndMentionHandlePresent() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode paths = root.get("paths");
    // Four new endpoints with distinct, stable operationIds.
    assertThat(paths.path("/api/tickets/{id}/comments").path("post").path("operationId").asString())
        .isEqualTo("createComment");
    assertThat(paths.path("/api/tickets/{id}/comments").path("get").path("operationId").asString())
        .isEqualTo("listComments");
    assertThat(paths.path("/api/comments/{id}").path("patch").path("operationId").asString())
        .isEqualTo("updateComment");
    assertThat(paths.path("/api/comments/{id}").path("delete").path("operationId").asString())
        .isEqualTo("deleteComment");

    // 403 documented on the edit/delete (author-or-admin) endpoints.
    assertThat(paths.path("/api/comments/{id}").path("patch").path("responses").has("403")).isTrue();
    assertThat(paths.path("/api/comments/{id}").path("delete").path("responses").has("403"))
        .isTrue();

    JsonNode schemas = root.get("components").get("schemas");
    assertThat(schemas.has("CommentResponse")).as("CommentResponse schema").isTrue();
    assertThat(schemas.has("CreateCommentRequest")).as("CreateCommentRequest schema").isTrue();
    assertThat(schemas.has("UpdateCommentRequest")).as("UpdateCommentRequest schema").isTrue();

    // mentionHandle is now part of the member-list contract.
    assertThat(schemas.path("MemberResponse").path("properties").has("mentionHandle"))
        .as("MemberResponse.mentionHandle")
        .isTrue();
  }

  @Test
  void t023WatcherAndPublicConfigEndpointsPresent() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode paths = root.get("paths");
    assertThat(paths.path("/api/tickets/{id}/watch").path("put").path("operationId").asString())
        .isEqualTo("watchTicket");
    assertThat(paths.path("/api/tickets/{id}/watch").path("delete").path("operationId").asString())
        .isEqualTo("unwatchTicket");
    assertThat(paths.path("/api/tickets/{id}/watchers").path("get").path("operationId").asString())
        .isEqualTo("listTicketWatchers");
    assertThat(paths.path("/api/config/public").path("get").path("operationId").asString())
        .isEqualTo("getPublicConfig");

    // The public config endpoint is documented WITHOUT a bearerAuth security block.
    assertThat(paths.path("/api/config/public").path("get").has("security")).isFalse();

    assertThat(root.path("components").path("schemas").has("PublicConfigResponse"))
        .as("PublicConfigResponse schema")
        .isTrue();
  }

  @Test
  void t024NotificationEndpointsPresentAndNoUnreadCountEndpoint() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode paths = root.get("paths");
    // The three notification endpoints with distinct, stable operationIds.
    assertThat(paths.path("/api/notifications").path("get").path("operationId").asString())
        .isEqualTo("listNotifications");
    assertThat(
            paths
                .path("/api/notifications/{id}/read")
                .path("post")
                .path("operationId")
                .asString())
        .isEqualTo("markNotificationRead");
    assertThat(
            paths.path("/api/notifications/read-all").path("post").path("operationId").asString())
        .isEqualTo("markAllNotificationsRead");

    // BLOCKING-1: there is deliberately NO unread-count endpoint (the badge uses the
    // list with unread=true&size=1 and reads `total`). Guard against one creeping in.
    assertThat(paths.has("/api/notifications/unread-count"))
        .as("there must be no dedicated unread-count endpoint")
        .isFalse();

    // The 404 (foreign/unknown id) is documented on mark-read.
    assertThat(paths.path("/api/notifications/{id}/read").path("post").path("responses").has("404"))
        .isTrue();
  }

  @Test
  void t025AttachmentEndpointsPresentWithStableOperationIds() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode paths = root.get("paths");
    assertThat(
            paths
                .path("/api/tickets/{id}/attachments/init")
                .path("post")
                .path("operationId")
                .asString())
        .isEqualTo("initAttachmentUpload");
    assertThat(
            paths.path("/api/attachments/{id}/finalize").path("post").path("operationId").asString())
        .isEqualTo("finalizeAttachment");
    assertThat(
            paths.path("/api/tickets/{id}/attachments").path("get").path("operationId").asString())
        .isEqualTo("listTicketAttachments");
    assertThat(
            paths
                .path("/api/attachments/{id}/download-url")
                .path("get")
                .path("operationId")
                .asString())
        .isEqualTo("getAttachmentDownloadUrl");
    assertThat(
            paths.path("/api/attachments/{id}").path("delete").path("operationId").asString())
        .isEqualTo("deleteAttachment");

    // 403 documented on delete (uploader-or-admin); 400 on init (allowlist/oversize).
    assertThat(
            paths.path("/api/attachments/{id}").path("delete").path("responses").has("403"))
        .isTrue();
    assertThat(
            paths
                .path("/api/tickets/{id}/attachments/init")
                .path("post")
                .path("responses")
                .has("400"))
        .isTrue();

    JsonNode schemas = root.get("components").get("schemas");
    assertThat(schemas.has("InitUploadRequest")).as("InitUploadRequest schema").isTrue();
    assertThat(schemas.has("InitUploadResponse")).as("InitUploadResponse schema").isTrue();
    assertThat(schemas.has("AttachmentResponse")).as("AttachmentResponse schema").isTrue();
    assertThat(schemas.has("FinalizeResponse")).as("FinalizeResponse schema").isTrue();
  }

  @Test
  void meAndLogoutDeclareBearerAuthSecurityRequirement() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            given().when().get("/v3/api-docs").then().statusCode(200).extract().asString());

    JsonNode meSecurity = root.path("paths").path("/api/auth/me").path("get").path("security");
    assertThat(meSecurity.isMissingNode())
        .as("/api/auth/me GET must declare a `security` array")
        .isFalse();
    assertThat(meSecurity.isArray() && !meSecurity.isEmpty()).isTrue();
    assertThat(meSecurity.path(0).has("bearerAuth")).isTrue();

    JsonNode logoutSecurity =
        root.path("paths").path("/api/auth/logout").path("post").path("security");
    assertThat(logoutSecurity.isMissingNode())
        .as("/api/auth/logout POST must declare a `security` array")
        .isFalse();
    assertThat(logoutSecurity.isArray() && !logoutSecurity.isEmpty()).isTrue();
    assertThat(logoutSecurity.path(0).has("bearerAuth")).isTrue();

    JsonNode securitySchemes = root.path("components").path("securitySchemes");
    assertThat(securitySchemes.isMissingNode())
        .as("components.securitySchemes must be present")
        .isFalse();
    assertThat(securitySchemes.has("bearerAuth")).isTrue();
    assertThat(securitySchemes.path("bearerAuth").path("scheme").asString()).isEqualTo("bearer");
    assertThat(securitySchemes.path("bearerAuth").path("bearerFormat").asString()).isEqualTo("JWT");
  }
}
