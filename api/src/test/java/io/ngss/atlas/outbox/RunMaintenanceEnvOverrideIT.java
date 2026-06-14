package io.ngss.atlas.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * AC-1 / AC-3 env wiring: {@code OUTBOX_RECLAIM_AFTER_MINUTES} and
 * {@code ATTACHMENT_PENDING_EXPIRY_HOURS} are honoured (not hard-coded). This is a SEPARATE
 * context — {@code @TestPropertySource} here MERGES with {@link OutboxITBase}'s — with both
 * windows shrunk to 1, so rows that the default 15-min / 24-hour windows would leave alone are
 * now swept. (The shared-context ITs can't override env, hence a dedicated class.)
 */
@TestPropertySource(
    properties = {"OUTBOX_RECLAIM_AFTER_MINUTES=1", "ATTACHMENT_PENDING_EXPIRY_HOURS=1"})
class RunMaintenanceEnvOverrideIT extends OutboxITBase {

  @Test
  void reclaimWindowOverride_reclaimsRowsTheDefaultWouldSkip() {
    // 5 min old: WITHIN the default 15-min window (would NOT reclaim) but PAST the overridden 1 min.
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO outbox (id, kind, status, payload, attempt_count, updated_at) "
            + "VALUES (?::uuid, 'EMAIL_NOTIFICATION', 'PROCESSING', '{}'::jsonb, 0, now() - interval '5 minutes')",
        id.toString());

    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("reclaimedToPending", equalTo(1));
    assertThat(outboxStatus(id)).isEqualTo("PENDING");
  }

  @Test
  void expiryWindowOverride_expiresUploadsTheDefaultWouldSkip() {
    UUID uploader = register("uploader@example.com", "Uploader");
    String token = sign(uploader);
    String projectId = createProject(token, "ENG", "Engineering");
    UUID ticketId = UUID.fromString(createTicket(token, projectId, "{\"title\":\"T\"}"));

    // 2 h old: WITHIN the default 24-h window (would NOT expire) but PAST the overridden 1 h.
    UUID att = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO attachments "
            + "(id, ticket_id, uploaded_by, object_key, filename, content_type, size_bytes, status, created_at) "
            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'f.png', 'image/png', 123, 'PENDING', now() - interval '2 hours')",
        att.toString(),
        ticketId.toString(),
        uploader.toString(),
        "tickets/" + ticketId + "/" + att + "/f.png");

    runMaintenance(DRAIN_SECRET).then().statusCode(200).body("expiredUploads", equalTo(1));
    assertThat(countOutboxByKind(OutboxKind.ATTACHMENT_DELETE_OBJECT)).isEqualTo(1);
  }
}
