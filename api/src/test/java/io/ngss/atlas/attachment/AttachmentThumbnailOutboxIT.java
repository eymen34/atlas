package io.ngss.atlas.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.ngss.atlas.outbox.OutboxDrainService;
import io.ngss.atlas.outbox.OutboxKind;
import io.ngss.atlas.outbox.OutboxRow;
import io.ngss.atlas.outbox.OutboxStatus;
import io.restassured.response.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

/**
 * T-040 outbox thumbnail flow: finalize enqueues exactly one ATTACHMENT_THUMBNAIL row in the
 * finalize transaction; the drain handler GETs the source, generates the JPEG, PUTs it, and marks
 * {@code thumbnail_object_key}; transient S3 faults throw (→ outbox retry) while permanent faults
 * (NoSuchKey / decompression bomb / corrupt image) leave a NULL key and the row goes SENT; re-drain
 * is idempotent. The source object lives in a real MinIO; one spy on the app {@link S3Client} forces
 * the fault paths.
 */
class AttachmentThumbnailOutboxIT extends AttachmentITBase {

  // Spy the internal S3Client so individual tests can force a transient/NoSuchKey GET fault.
  // Unstubbed, the spy delegates to the real MinIO-backed client (generate path).
  @MockitoSpyBean S3Client s3Client;

  @Autowired OutboxDrainService drainService;
  @Autowired AttachmentThumbnailHandler handler;
  @Autowired ObjectMapper objectMapper;

  private record Fixture(String token, String ticket) {}

  private Fixture aliceWithTicket() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    String ticket = createTicket(token, eng, "Images");
    return new Fixture(token, ticket);
  }

  // ───────────────────────── AC-1: enqueue ─────────────────────────

  @Test
  void confirmEnqueuesExactlyOneOutboxRow_whenFlagOn() {
    Fixture f = aliceWithTicket();
    uploadImage(f, "pic.png", png(80, 60, BufferedImage.TYPE_INT_RGB));

    assertThat(countThumbnailRows()).isEqualTo(1L);
    assertThat(thumbnailRowStatus()).isEqualTo("PENDING");
    assertThat(thumbnailRowAttemptCount()).isZero();
  }

  // ───────────────────────── AC-2: generate / faults ─────────────────────────

  @Test
  void drain_generatesThumbnailInMinio_andMarksRowSent() {
    Fixture f = aliceWithTicket();
    String id = uploadImage(f, "pic.png", png(80, 60, BufferedImage.TYPE_INT_RGB));

    drainService.drain(10);

    String key = thumbnailKey(id);
    assertThat(key).isEqualTo("thumbnails/" + id + ".jpg");
    assertThat(objectExists(key)).isTrue();
    assertThat(thumbnailRowStatus()).isEqualTo("SENT");
    // The thumbnail is now downloadable.
    downloadUrl(f.token(), id, "?thumbnail=true").then().statusCode(200);
  }

  @Test
  void transientS3Failure_handleThrows_andOutboxRetries() {
    Fixture f = aliceWithTicket();
    String id = uploadImage(f, "pic.png", png(80, 60, BufferedImage.TYPE_INT_RGB));

    doThrow(S3Exception.builder().statusCode(503).message("simulated 5xx").build())
        .when(s3Client)
        .getObjectAsBytes(any(GetObjectRequest.class));

    // The handler THROWS on a transient 5xx (it does NOT swallow → return SENT).
    assertThatThrownBy(() -> handler.handle(thumbnailRow(id))).isInstanceOf(S3Exception.class);

    // Driven through the real drain, the throw becomes a backed-off retry: PENDING, attempt_count=1.
    drainService.drain(10);
    assertThat(thumbnailKey(id)).isNull();
    assertThat(thumbnailRowStatus()).isEqualTo("PENDING");
    assertThat(thumbnailRowAttemptCount()).isEqualTo(1);
  }

  @Test
  void noSuchKey_isPermanent_rowSent_noThumbnail() {
    Fixture f = aliceWithTicket();
    String id = uploadImage(f, "pic.png", png(80, 60, BufferedImage.TYPE_INT_RGB));

    doThrow(NoSuchKeyException.builder().message("gone").build())
        .when(s3Client)
        .getObjectAsBytes(any(GetObjectRequest.class));

    drainService.drain(10);

    assertThat(thumbnailKey(id)).isNull();
    assertThat(thumbnailRowStatus()).isEqualTo("SENT"); // permanent: handled, no retry
  }

  @Test
  void headerBomb_skipsThumbnail_rowSent() {
    Fixture f = aliceWithTicket();
    // 4200x4200 → 4200*4200*4 ≈ 70 MiB decoded (> 64 MiB guard). GRAY keeps the TEST heap small;
    // the PNG header still declares the large dimensions the guard reads before decoding.
    String id = uploadImage(f, "bomb.png", png(4200, 4200, BufferedImage.TYPE_BYTE_GRAY));

    drainService.drain(10);

    assertThat(thumbnailKey(id)).isNull();
    assertThat(objectExists("thumbnails/" + id + ".jpg")).isFalse();
    assertThat(thumbnailRowStatus()).isEqualTo("SENT");
  }

  @Test
  void corruptImage_skipsThumbnail_rowSent() {
    Fixture f = aliceWithTicket();
    // 512 deterministic non-image bytes uploaded as image/png: finalize HEAD passes (the signed
    // Content-Type matches), but ImageIO finds no reader for the bytes → permanent skip.
    byte[] junk = new byte[512];
    for (int i = 0; i < junk.length; i++) {
      junk[i] = (byte) (i % 256);
    }
    String id = uploadImage(f, "corrupt.png", junk);

    drainService.drain(10);

    assertThat(thumbnailKey(id)).isNull();
    assertThat(thumbnailRowStatus()).isEqualTo("SENT");
  }

  // ───────────────────────── AC-3: idempotency ─────────────────────────

  @Test
  void reDrain_isNoop_noDuplicateObject() throws Exception {
    Fixture f = aliceWithTicket();
    String id = uploadImage(f, "pic.png", png(80, 60, BufferedImage.TYPE_INT_RGB));

    drainService.drain(10); // first pass: generates + PUTs once
    assertThat(thumbnailKey(id)).isEqualTo("thumbnails/" + id + ".jpg");

    // Re-handle the SAME attachment (terminal: key already set) — the gate short-circuits before S3.
    handler.handle(thumbnailRow(id));

    verify(s3Client, times(1))
        .putObject(any(PutObjectRequest.class), any(RequestBody.class)); // not doubled
    assertThat(thumbnailKey(id)).isEqualTo("thumbnails/" + id + ".jpg"); // unchanged
  }

  // ───────────────────────── helpers ─────────────────────────

  private String uploadImage(Fixture f, String filename, byte[] body) {
    Response init = initUpload(f.token(), f.ticket(), filename, "image/png", body.length);
    init.then().statusCode(201);
    String id = init.jsonPath().getString("attachmentId");
    assertThat(httpPut(init.jsonPath().getString("uploadUrl"), body, "image/png")).isEqualTo(200);
    finalizeUpload(f.token(), id).then().statusCode(200).body("status", equalTo("READY"));
    return id;
  }

  private OutboxRow thumbnailRow(String attachmentId) {
    return new OutboxRow(
        UUID.randomUUID(),
        OutboxKind.ATTACHMENT_THUMBNAIL,
        OutboxStatus.PENDING,
        objectMapper.valueToTree(AttachmentThumbnailPayload.of(UUID.fromString(attachmentId))),
        0,
        Instant.now(),
        null,
        Instant.now(),
        Instant.now(),
        null);
  }

  private static byte[] png(int w, int h, int type) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(w, h, type), "png", baos);
      return baos.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private long countThumbnailRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox WHERE kind = 'ATTACHMENT_THUMBNAIL'", Long.class);
  }

  private String thumbnailRowStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM outbox WHERE kind = 'ATTACHMENT_THUMBNAIL'", String.class);
  }

  private int thumbnailRowAttemptCount() {
    return jdbc.queryForObject(
        "SELECT attempt_count FROM outbox WHERE kind = 'ATTACHMENT_THUMBNAIL'", Integer.class);
  }
}
