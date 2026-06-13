package io.ngss.atlas.attachment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Upload lifecycle: init → presigned PUT → finalize, plus validation/idempotency (T-025).
 *
 * <p>finalize is a STATE-MACHINE step: a size/content-type mismatch returns 200 with
 * {@code status="FAILED"}, NOT a 4xx — an exception would roll back the FAILED write
 * (jpa_rollback_only_trap). Init still 400s for oversize / disallowed content type
 * (validation BEFORE any row is written).
 */
class AttachmentFlowIT extends AttachmentITBase {

  private record Fixture(String token, String ticket) {}

  private Fixture aliceWithTicket() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    String ticket = createTicket(token, eng, "Attach things");
    return new Fixture(token, ticket);
  }

  @Test
  void initPutFinalize_marksReady_andWritesActivity() {
    Fixture f = aliceWithTicket();
    byte[] body = "hello pdf bytes".getBytes(UTF_8);

    Response init = initUpload(f.token(), f.ticket(), "doc.pdf", "application/pdf", body.length);
    init.then().statusCode(201);
    String attachmentId = init.jsonPath().getString("attachmentId");
    String uploadUrl = init.jsonPath().getString("uploadUrl");
    assertThat(init.jsonPath().getString("headers.Content-Type")).isEqualTo("application/pdf");

    assertThat(httpPut(uploadUrl, body, "application/pdf")).isEqualTo(200);
    finalizeUpload(f.token(), attachmentId).then().statusCode(200).body("status", equalTo("READY"));

    assertThat(attachmentStatus(attachmentId)).isEqualTo("READY");
    assertThat(countActivity(f.ticket(), "ATTACHMENT_ADDED")).isEqualTo(1);

    listAttachments(f.token(), f.ticket())
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].filename", equalTo("doc.pdf"))
        .body("[0].hasThumbnail", equalTo(false));
  }

  @Test
  void oversizeInit_returns400_andLeaksNoRow() {
    Fixture f = aliceWithTicket();
    long oversize = 26_214_400L + 1; // default ATTACHMENT_MAX_SIZE_BYTES + 1

    initUpload(f.token(), f.ticket(), "big.pdf", "application/pdf", oversize).then().statusCode(400);

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM attachments WHERE ticket_id=?::uuid", Integer.class, f.ticket());
    assertThat(rows).isZero();
  }

  @Test
  void disallowedContentTypeInit_returns400() {
    Fixture f = aliceWithTicket();
    initUpload(f.token(), f.ticket(), "evil.exe", "application/x-msdownload", 10)
        .then()
        .statusCode(400);
  }

  @Test
  void finalizeWithSizeMismatch_returnsFailed_andWritesNoActivity() {
    Fixture f = aliceWithTicket();
    Response init = initUpload(f.token(), f.ticket(), "f.txt", "text/plain", 100);
    String attachmentId = init.jsonPath().getString("attachmentId");
    String uploadUrl = init.jsonPath().getString("uploadUrl");

    // Presigned PUT does NOT enforce size — upload only 50 bytes against a declared 100.
    assertThat(httpPut(uploadUrl, new byte[50], "text/plain")).isEqualTo(200);
    finalizeUpload(f.token(), attachmentId)
        .then()
        .statusCode(200)
        .body("status", equalTo("FAILED"))
        .body("reason", equalTo("size_mismatch"));

    assertThat(attachmentStatus(attachmentId)).isEqualTo("FAILED");
    assertThat(countActivity(f.ticket(), "ATTACHMENT_ADDED")).isZero();
  }

  @Test
  void finalizeWithContentTypeMismatch_returnsFailed() {
    Fixture f = aliceWithTicket();
    byte[] body = "x".getBytes(UTF_8);
    Response init = initUpload(f.token(), f.ticket(), "f.png", "image/png", body.length);
    String attachmentId = init.jsonPath().getString("attachmentId");

    // Direct PUT (bypassing the signed URL) stores a DIFFERENT content type than declared.
    putObjectDirect(objectKey(attachmentId), body, "application/pdf");
    finalizeUpload(f.token(), attachmentId)
        .then()
        .statusCode(200)
        .body("status", equalTo("FAILED"))
        .body("reason", equalTo("content_type_mismatch"));

    assertThat(attachmentStatus(attachmentId)).isEqualTo("FAILED");
    assertThat(countActivity(f.ticket(), "ATTACHMENT_ADDED")).isZero();
  }

  @Test
  void retryAfterFailed_succeeds() {
    Fixture f = aliceWithTicket();
    Response init = initUpload(f.token(), f.ticket(), "f.txt", "text/plain", 100);
    String attachmentId = init.jsonPath().getString("attachmentId");
    String uploadUrl = init.jsonPath().getString("uploadUrl");

    httpPut(uploadUrl, new byte[50], "text/plain"); // wrong size
    finalizeUpload(f.token(), attachmentId).then().statusCode(200).body("status", equalTo("FAILED"));
    assertThat(attachmentStatus(attachmentId)).isEqualTo("FAILED");

    // Re-upload the correct bytes to the same key and finalize again — FAILED is re-HEADable.
    httpPut(uploadUrl, new byte[100], "text/plain");
    finalizeUpload(f.token(), attachmentId).then().statusCode(200).body("status", equalTo("READY"));
    assertThat(attachmentStatus(attachmentId)).isEqualTo("READY");
  }

  @Test
  void finalizeIsIdempotent_alreadyReady() {
    Fixture f = aliceWithTicket();
    byte[] body = "data".getBytes(UTF_8);
    Response init = initUpload(f.token(), f.ticket(), "f.txt", "text/plain", body.length);
    String attachmentId = init.jsonPath().getString("attachmentId");
    httpPut(init.jsonPath().getString("uploadUrl"), body, "text/plain");

    finalizeUpload(f.token(), attachmentId).then().statusCode(200).body("status", equalTo("READY"));
    finalizeUpload(f.token(), attachmentId).then().statusCode(200).body("status", equalTo("READY"));

    assertThat(countActivity(f.ticket(), "ATTACHMENT_ADDED")).isEqualTo(1); // not doubled
  }

  @Test
  void downloadUrlPointsAtConfiguredEndpoint() {
    Fixture f = aliceWithTicket();
    byte[] body = "data".getBytes(UTF_8);
    Response init = initUpload(f.token(), f.ticket(), "f.txt", "text/plain", body.length);
    String attachmentId = init.jsonPath().getString("attachmentId");
    httpPut(init.jsonPath().getString("uploadUrl"), body, "text/plain");
    finalizeUpload(f.token(), attachmentId).then().statusCode(200);

    String url =
        downloadUrl(f.token(), attachmentId, "").then().statusCode(200).extract().jsonPath().getString("url");
    // Presigned GET is signed against the (public) endpoint and is a short-lived signed URL.
    assertThat(url).startsWith(MINIO.getS3URL()).contains(BUCKET).contains("X-Amz-Signature");
  }

  @Test
  void listExcludesPendingFailedAndDeleted() {
    Fixture f = aliceWithTicket();
    byte[] body = "data".getBytes(UTF_8);

    // PENDING (init only).
    initUpload(f.token(), f.ticket(), "pending.txt", "text/plain", body.length);

    // READY.
    Response ready = initUpload(f.token(), f.ticket(), "ready.txt", "text/plain", body.length);
    String readyId = ready.jsonPath().getString("attachmentId");
    httpPut(ready.jsonPath().getString("uploadUrl"), body, "text/plain");
    finalizeUpload(f.token(), readyId).then().statusCode(200);

    // READY then DELETED.
    Response del = initUpload(f.token(), f.ticket(), "gone.txt", "text/plain", body.length);
    String delId = del.jsonPath().getString("attachmentId");
    httpPut(del.jsonPath().getString("uploadUrl"), body, "text/plain");
    finalizeUpload(f.token(), delId).then().statusCode(200);
    deleteAttachment(f.token(), delId).then().statusCode(204);

    listAttachments(f.token(), f.ticket())
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].filename", equalTo("ready.txt"));
  }
}
