package io.ngss.atlas.outbox;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * AC-7: attachment soft-delete enqueues an ATTACHMENT_DELETE_OBJECT row in the SAME transaction
 * as {@code deleted_at}; draining it calls {@code S3Client.deleteObject} for the object key AND
 * the thumbnail key, and the row goes SENT. The READY attachment is seeded via JDBC (the full
 * init/finalize flow needs a live S3); the delete + drain are exercised end to end.
 */
class AttachmentDeleteEnqueueIT extends OutboxITBase {

  @MockitoBean S3Client s3Client;

  @Test
  void softDeleteEnqueuesAndDrainRemovesBothObjects() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    String ticket = createTicket(token, eng, "{\"title\":\"T\"}");

    UUID attachmentId = UUID.randomUUID();
    String objectKey = "tickets/" + ticket + "/" + attachmentId + "/file.png";
    String thumbnailKey = "thumbnails/" + attachmentId + ".jpg";
    jdbc.update(
        "INSERT INTO attachments (id, ticket_id, uploaded_by, object_key, filename, content_type, "
            + "size_bytes, status, thumbnail_object_key, created_at, finalized_at) "
            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, 'READY', ?, now(), now())",
        attachmentId.toString(),
        ticket,
        alice.toString(),
        objectKey,
        "file.png",
        "image/png",
        123L,
        thumbnailKey);

    // Alice is the project ADMIN → delete allowed → 204.
    given()
        .header("Authorization", "Bearer " + token)
        .delete("/api/attachments/" + attachmentId)
        .then()
        .statusCode(204);

    // Enqueued in the same transaction as the soft-delete.
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1L);

    drainOutbox(DRAIN_SECRET).then().statusCode(200).body("succeeded", equalTo(1));

    ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client, times(2)).deleteObject(captor.capture());
    assertThat(captor.getAllValues().stream().map(DeleteObjectRequest::key))
        .containsExactlyInAnyOrder(objectKey, thumbnailKey);

    UUID rowId =
        jdbc.queryForObject(
            "SELECT id FROM outbox WHERE kind = 'ATTACHMENT_DELETE_OBJECT'", UUID.class);
    assertThat(outboxStatus(rowId)).isEqualTo("SENT");
  }
}
