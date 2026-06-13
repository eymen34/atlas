package io.ngss.atlas.attachment;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Authorization matrix for the attachment endpoints (T-025, AC-5, SEC). */
class AttachmentAuthzIT extends AttachmentITBase {

  private String upload(String token, String ticket, String filename) {
    byte[] body = "data".getBytes(UTF_8);
    Response init = initUpload(token, ticket, filename, "text/plain", body.length);
    String id = init.jsonPath().getString("attachmentId");
    httpPut(init.jsonPath().getString("uploadUrl"), body, "text/plain");
    finalizeUpload(token, id).then().statusCode(204);
    return id;
  }

  @Test
  void nonMemberIs404Everywhere() {
    UUID alice = register("alice@example.com", "Alice");
    UUID carol = register("carol@example.com", "Carol"); // never added to the project
    String alaceToken = sign(alice);
    String carolToken = sign(carol);
    String eng = createProject(alaceToken, "ENG", "Engineering");
    String ticket = createTicket(alaceToken, eng, "T");
    String attachmentId = upload(alaceToken, ticket, "a.txt");
    String pending = initUpload(alaceToken, ticket, "p.txt", "text/plain", 4).jsonPath().getString("attachmentId");

    initUpload(carolToken, ticket, "x.txt", "text/plain", 4).then().statusCode(404);
    listAttachments(carolToken, ticket).then().statusCode(404);
    downloadUrl(carolToken, attachmentId, "").then().statusCode(404); // IDOR-safe
    finalizeUpload(carolToken, pending).then().statusCode(404);
    deleteAttachment(carolToken, attachmentId).then().statusCode(404);
  }

  @Test
  void finalizeByNonUploaderMemberIs404() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String alaceToken = sign(alice);
    String bobToken = sign(bob);
    String eng = createProject(alaceToken, "ENG", "Engineering");
    addMember(alaceToken, eng, "bob@example.com");
    String ticket = createTicket(alaceToken, eng, "T");

    // alice's PENDING upload; bob is a member but NOT the uploader.
    String pending =
        initUpload(alaceToken, ticket, "p.txt", "text/plain", 4).jsonPath().getString("attachmentId");

    finalizeUpload(bobToken, pending).then().statusCode(404);
  }

  @Test
  void deleteByNonUploaderNonAdminIs403_butAdminSucceeds() {
    UUID alice = register("alice@example.com", "Alice"); // creator → ADMIN
    UUID bob = register("bob@example.com", "Bob"); // MEMBER
    String alaceToken = sign(alice);
    String bobToken = sign(bob);
    String eng = createProject(alaceToken, "ENG", "Engineering");
    addMember(alaceToken, eng, "bob@example.com");
    String ticket = createTicket(alaceToken, eng, "T");

    String aliceFile = upload(alaceToken, ticket, "alice.txt");
    String bobFile = upload(bobToken, ticket, "bob.txt");

    // bob (member, not admin, not uploader of aliceFile) → 403.
    deleteAttachment(bobToken, aliceFile).then().statusCode(403);
    // alice (ADMIN) can delete bob's file even though she is not the uploader.
    deleteAttachment(alaceToken, bobFile).then().statusCode(204);
  }

  @Test
  void allEndpointsRequireAuthentication() {
    String ticket = UUID.randomUUID().toString();
    String attachment = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body("{\"filename\":\"a\",\"contentType\":\"text/plain\",\"sizeBytes\":1}")
        .post("/api/tickets/" + ticket + "/attachments/init")
        .then()
        .statusCode(401);
    given().get("/api/tickets/" + ticket + "/attachments").then().statusCode(401);
    given().post("/api/attachments/" + attachment + "/finalize").then().statusCode(401);
    given().get("/api/attachments/" + attachment + "/download-url").then().statusCode(401);
    given().delete("/api/attachments/" + attachment).then().statusCode(401);
  }
}
