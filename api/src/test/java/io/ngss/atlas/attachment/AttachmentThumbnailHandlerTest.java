package io.ngss.atlas.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.Attachment;
import io.ngss.atlas.domain.AttachmentStatus;
import io.ngss.atlas.outbox.OutboxKind;
import io.ngss.atlas.outbox.OutboxRow;
import io.ngss.atlas.outbox.OutboxStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure-logic unit tests for {@link AttachmentThumbnailHandler} (T-040): the decompression-bomb
 * budget arithmetic, the deterministic key formula, and the idempotency/missing-row entry gates.
 * The S3-touching paths (generate, transient retry, NoSuchKey) are covered by the MinIO IT.
 */
class AttachmentThumbnailHandlerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // ───────────────────────── bomb-guard arithmetic (EC-8, SEC-3) ─────────────────────────

  @Test
  void bombGuard_exactBoundary_notRejected() {
    // 4096 * 4096 * 4 = exactly 64 MiB — NOT strictly greater than the budget, so it is accepted.
    assertThat(AttachmentThumbnailHandler.exceedsDecodedBudget(4096, 4096)).isFalse();
  }

  @Test
  void bombGuard_overBoundary_rejected() {
    // 4097 * 4096 * 4 = 64 MiB + 16 KiB — over budget, rejected.
    assertThat(AttachmentThumbnailHandler.exceedsDecodedBudget(4097, 4096)).isTrue();
  }

  @Test
  void bombGuard_intOverflowSafe() {
    // 50000 * 50000 = 2.5e9 px: the 32-bit int pixel product would overflow (wrap negative) and
    // slip past a naive int guard; the long multiplication keeps it positive and rejects it.
    assertThat(AttachmentThumbnailHandler.exceedsDecodedBudget(50_000, 50_000)).isTrue();
    assertThat(AttachmentThumbnailHandler.exceedsDecodedBudget(Integer.MAX_VALUE, 2)).isTrue();
  }

  // ───────────────────────── deterministic key (EC-10) ─────────────────────────

  @Test
  void deterministicKey_formula() {
    UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    assertThat(AttachmentThumbnailHandler.thumbnailKey(id))
        .isEqualTo("thumbnails/12345678-1234-1234-1234-123456789abc.jpg");
  }

  // ───────────────────────── entry gates (AC-3 idempotency) ─────────────────────────

  @Test
  void alreadyHasThumbnail_isNoop_neverTouchesS3() throws Exception {
    UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    Attachment attachment = readyImage(id);
    attachment.attachThumbnail("thumbnails/" + id + ".jpg"); // already generated → terminal

    AttachmentRepository repo = mock(AttachmentRepository.class);
    when(repo.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(attachment));
    S3Client s3 = mock(S3Client.class);

    newHandler(repo, s3).handle(rowFor(id));

    verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
  }

  @Test
  void missingAttachment_isNoop_neverTouchesS3() throws Exception {
    UUID id = UUID.randomUUID();
    AttachmentRepository repo = mock(AttachmentRepository.class);
    when(repo.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());
    S3Client s3 = mock(S3Client.class);

    newHandler(repo, s3).handle(rowFor(id));

    verify(s3, never()).getObjectAsBytes(any(GetObjectRequest.class));
  }

  @Test
  void kind_isAttachmentThumbnail() {
    assertThat(newHandler(mock(AttachmentRepository.class), mock(S3Client.class)).kind())
        .isEqualTo(OutboxKind.ATTACHMENT_THUMBNAIL);
  }

  // ───────────────────────── helpers ─────────────────────────

  private static AttachmentThumbnailHandler newHandler(AttachmentRepository repo, S3Client s3) {
    ObjectStorageProperties props =
        new ObjectStorageProperties(
            "http://minio", "http://minio", "us-east-1", "bucket", "ak", "sk", 26_214_400L);
    AttachmentThumbnailHandler handler = new AttachmentThumbnailHandler(repo, props, MAPPER);
    ReflectionTestUtils.setField(handler, "s3Client", s3);
    ReflectionTestUtils.setField(handler, "self", handler);
    return handler;
  }

  private static OutboxRow rowFor(UUID attachmentId) {
    return new OutboxRow(
        UUID.randomUUID(),
        OutboxKind.ATTACHMENT_THUMBNAIL,
        OutboxStatus.PENDING,
        MAPPER.valueToTree(AttachmentThumbnailPayload.of(attachmentId)),
        0,
        Instant.now(),
        null,
        Instant.now(),
        Instant.now(),
        null);
  }

  private static Attachment readyImage(UUID id) {
    return new Attachment(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "tickets/x/" + id + "/p.png",
        "p.png",
        "image/png",
        123L,
        AttachmentStatus.READY,
        Instant.now());
  }
}
