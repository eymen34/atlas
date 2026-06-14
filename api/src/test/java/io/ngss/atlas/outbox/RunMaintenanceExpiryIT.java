package io.ngss.atlas.outbox;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.ngss.atlas.attachment.AttachmentService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AC-3 / EC-5 / REG-2: the PENDING-upload-expiry half of {@code run-maintenance}. An abandoned
 * PENDING attachment older than {@code ATTACHMENT_PENDING_EXPIRY_HOURS} (default 24) is soft-deleted
 * and enqueues EXACTLY ONE {@code ATTACHMENT_DELETE_OBJECT} via the existing AttachmentService path;
 * a re-run is a no-op. Attachments are seeded via raw SQL (no S3 dependency).
 */
class RunMaintenanceExpiryIT extends OutboxITBase {

  @Autowired AttachmentService attachmentService;

  private record Fixture(UUID uploader, String token, UUID ticketId) {}

  private Fixture fixture() {
    UUID uploader = register("uploader@example.com", "Uploader");
    String token = sign(uploader);
    String projectId = createProject(token, "ENG", "Engineering");
    String ticketId = createTicket(token, projectId, "{\"title\":\"T\"}");
    return new Fixture(uploader, token, UUID.fromString(ticketId));
  }

  /** Seeds one attachment of the given status, aged so created_at = now() - {@code age}. */
  private UUID seedAttachment(Fixture f, String status, String age) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO attachments "
            + "(id, ticket_id, uploaded_by, object_key, filename, content_type, size_bytes, status, created_at) "
            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'f.png', 'image/png', 123, ?, now() - CAST(? AS interval))",
        id.toString(),
        f.ticketId().toString(),
        f.uploader().toString(),
        "tickets/" + f.ticketId() + "/" + id + "/f.png",
        status,
        age);
    return id;
  }

  private Instant deletedAt(UUID id) {
    return jdbc.queryForObject(
        "SELECT deleted_at FROM attachments WHERE id = ?::uuid", Instant.class, id.toString());
  }

  // ── AC-3.1: old PENDING → expired, soft-deleted, exactly one ATTACHMENT_DELETE_OBJECT ──
  @Test
  void oldPendingUpload_isExpiredAndEnqueuedOnce() {
    Fixture f = fixture();
    UUID att = seedAttachment(f, "PENDING", "25 hours");

    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("expiredUploads", equalTo(1));

    assertThat(deletedAt(att)).as("attachment soft-deleted").isNotNull();
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);
  }

  // ── AC-3.2: a second run is a no-op (no duplicate enqueue, deleted_at unchanged) ──
  @Test
  void secondRun_isNoOp() {
    Fixture f = fixture();
    UUID att = seedAttachment(f, "PENDING", "25 hours");
    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("expiredUploads", equalTo(1));
    Instant firstDeletedAt = deletedAt(att);

    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("expiredUploads", equalTo(0));

    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);
    assertThat(deletedAt(att)).isEqualTo(firstDeletedAt); // unchanged
  }

  // ── only old + PENDING + live rows are selected ──
  @Test
  void readyFreshAndAlreadyDeleted_areNotExpired() {
    Fixture f = fixture();
    UUID ready = seedAttachment(f, "READY", "25 hours"); // wrong status
    UUID fresh = seedAttachment(f, "PENDING", "1 hour"); // within window
    UUID gone = seedAttachment(f, "PENDING", "25 hours");
    jdbc.update(
        "UPDATE attachments SET deleted_at = now() WHERE id = ?::uuid", gone.toString()); // already deleted

    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("expiredUploads", equalTo(0));

    assertThat(deletedAt(ready)).isNull();
    assertThat(deletedAt(fresh)).isNull();
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isZero();
  }

  // ── EC-5: softDeleteSystem is idempotent on an already-deleted row (concurrent-race guard) ──
  @Test
  void softDeleteSystem_isIdempotent() {
    Fixture f = fixture();
    UUID att = seedAttachment(f, "PENDING", "25 hours");

    attachmentService.softDeleteSystem(att);
    assertThat(deletedAt(att)).isNotNull();
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);

    attachmentService.softDeleteSystem(att); // second call → no-op, no exception
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);
  }

  // ── REG-2: a user-deleted READY attachment is not re-expired (exactly one enqueue across paths) ──
  @Test
  void userDeletedReadyAttachment_isNotReExpired() {
    Fixture f = fixture();
    UUID att = seedAttachment(f, "READY", "25 hours");

    // User soft-deletes via the HTTP endpoint → one ATTACHMENT_DELETE_OBJECT enqueued.
    given()
        .header("Authorization", "Bearer " + f.token())
        .delete("/api/attachments/" + att)
        .then()
        .statusCode(204);
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);

    // The maintenance sweep must NOT touch it (not PENDING, already deleted).
    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("expiredUploads", equalTo(0));
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);
  }
}
