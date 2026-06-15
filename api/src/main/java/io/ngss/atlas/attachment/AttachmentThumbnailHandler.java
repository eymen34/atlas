package io.ngss.atlas.attachment;

import io.ngss.atlas.domain.Attachment;
import io.ngss.atlas.outbox.OutboxHandler;
import io.ngss.atlas.outbox.OutboxKind;
import io.ngss.atlas.outbox.OutboxRow;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

/**
 * Drains an {@link OutboxKind#ATTACHMENT_THUMBNAIL} row (T-040): GETs the source image, applies the
 * decompression-bomb header guard, generates a 256px JPEG thumbnail, PUTs it to S3, and records the
 * thumbnail key on the attachment. Replaces the old AFTER_COMMIT {@code AttachmentThumbnailListener};
 * the generation code ({@link #decodeWithBombGuard} + {@link #toJpegThumbnail}) is ported verbatim
 * from it — only the invocation moves event-listener → outbox-handler (plus transient retry).
 *
 * <p>The drain runs {@link #handle} OUTSIDE any transaction and turns a thrown exception into a
 * backed-off retry (FAILED at 10 attempts), or a normal return into SENT. So the fault model is:
 *
 * <ul>
 *   <li><b>Transient</b> (S3 5xx, timeout, {@code SdkClientException}) — THROW → outbox retry.
 *   <li><b>Permanent</b> (source {@code NoSuchKey}/4xx, no ImageIO reader, decompression bomb,
 *       corrupt/undecodable image) — return normally (row → SENT); the attachment keeps a NULL
 *       {@code thumbnail_object_key}, byte-identical to what the old listener left. There is NO
 *       separate SKIPPED/FAILED thumbnail state — "done" is {@code thumbnail_object_key != null}.
 * </ul>
 *
 * <p>{@code status_write_is_not_an_exception}: the only attachment write (the success path) goes
 * through {@link #attachThumbnail} in a fresh {@code REQUIRES_NEW} transaction via the {@code @Lazy
 * self} proxy — never combined with a throw. The two S3-touching collaborators are {@code @Lazy}
 * field-injected (not constructor params) so the Dockerfile stage-3 no-DB AppCDS boot never builds
 * the S3 client and the self-reference is wired without a circular-bean trap (appcds_boot_safety).
 */
@Component
public class AttachmentThumbnailHandler implements OutboxHandler {

  private static final Logger log = LoggerFactory.getLogger(AttachmentThumbnailHandler.class);
  private static final int LONGEST_EDGE = 256;
  private static final long MAX_DECODED_BYTES = 64L * 1024 * 1024; // 64 MiB
  private static final float JPEG_QUALITY = 0.85f;

  private final AttachmentRepository attachmentRepository;
  private final ObjectStorageProperties props;
  private final ObjectMapper objectMapper;

  @Autowired @Lazy private S3Client s3Client;
  @Autowired @Lazy private AttachmentThumbnailHandler self;

  public AttachmentThumbnailHandler(
      AttachmentRepository attachmentRepository,
      ObjectStorageProperties props,
      ObjectMapper objectMapper) {
    this.attachmentRepository = attachmentRepository;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  @Override
  public OutboxKind kind() {
    return OutboxKind.ATTACHMENT_THUMBNAIL;
  }

  @Override
  public void handle(OutboxRow row) throws Exception {
    AttachmentThumbnailPayload payload =
        objectMapper.treeToValue(row.payload(), AttachmentThumbnailPayload.class);
    UUID attachmentId = UUID.fromString(payload.attachmentId());

    Attachment attachment =
        attachmentRepository.findByIdAndDeletedAtIsNull(attachmentId).orElse(null);
    if (attachment == null) {
      // Soft-deleted before the drain ran — clean no-op; ATTACHMENT_DELETE_OBJECT handles cleanup.
      return;
    }
    if (attachment.getThumbnailObjectKey() != null) {
      return; // idempotent: thumbnail already generated (re-drain, or a concurrent run won)
    }

    byte[] original = fetchSource(attachment); // THROWS (transient) or returns null (permanent)
    if (original == null) {
      return;
    }

    byte[] jpeg;
    try {
      BufferedImage source = decodeWithBombGuard(original, attachmentId);
      if (source == null) {
        return; // no reader / decompression bomb — permanent skip, key stays NULL
      }
      jpeg = toJpegThumbnail(source);
    } catch (Exception decodeError) {
      // Corrupt/undecodable image (IIOException, IllegalArgumentException, …). The bytes are in
      // memory — nothing here is transient, so retrying cannot help: permanent skip, key NULL.
      log.warn(
          "T-040: thumbnail decode/encode failed for attachment={} — skipping",
          attachmentId,
          decodeError);
      return;
    }

    String thumbnailKey = thumbnailKey(attachmentId);
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(thumbnailKey)
            .contentType("image/jpeg")
            .build(),
        RequestBody.fromBytes(jpeg)); // any S3 error here propagates → transient retry

    self.attachThumbnail(attachmentId, thumbnailKey); // REQUIRES_NEW commit of the key
  }

  /**
   * GETs the source object bytes. Returns {@code null} for a PERMANENT fault (object gone, or a
   * 4xx) so the caller skips without retry; THROWS for a TRANSIENT fault (S3 5xx, or — by not
   * catching it — any {@code SdkClientException}) so the drain retries with backoff.
   */
  private byte[] fetchSource(Attachment attachment) {
    try {
      return s3Client
          .getObjectAsBytes(
              GetObjectRequest.builder()
                  .bucket(props.bucket())
                  .key(attachment.getObjectKey())
                  .build())
          .asByteArray();
    } catch (NoSuchKeyException gone) {
      log.warn(
          "T-040: source object missing for attachment={} — skipping thumbnail", attachment.getId());
      return null; // permanent
    } catch (S3Exception e) {
      if (e.statusCode() >= 500) {
        throw e; // transient → outbox retry/backoff
      }
      log.warn(
          "T-040: S3 {} on source GET for attachment={} — skipping thumbnail",
          e.statusCode(),
          attachment.getId());
      return null; // 4xx permanent
    }
  }

  /**
   * REQUIRES_NEW write of the generated thumbnail key. Package-private — invoked ONLY via the
   * {@code @Lazy self} proxy so it actually opens its own transaction (the drain runs {@link
   * #handle} transaction-free). Re-loads live so a soft-delete that raced the I/O is a no-op.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void attachThumbnail(UUID attachmentId, String thumbnailKey) {
    attachmentRepository
        .findByIdAndDeletedAtIsNull(attachmentId)
        .ifPresent(
            a -> {
              a.attachThumbnail(thumbnailKey);
              attachmentRepository.save(a);
            });
  }

  /** The deterministic thumbnail object key for an attachment ({@code thumbnails/{id}.jpg}). */
  static String thumbnailKey(UUID attachmentId) {
    return "thumbnails/" + attachmentId + ".jpg";
  }

  /**
   * Header-bomb budget check, extracted so the {@code long} arithmetic (the security-critical
   * guard) is unit-testable in isolation. {@code true} ⇒ the declared dimensions would decode to
   * more than {@link #MAX_DECODED_BYTES}. The {@code long} multiplication avoids the {@code int}
   * overflow that would let a large image wrap negative and slip past a naive {@code int} product.
   */
  static boolean exceedsDecodedBudget(long width, long height) {
    return width * height * 4L > MAX_DECODED_BYTES;
  }

  // ───── generation code ported verbatim from AttachmentThumbnailListener (T-025) ─────

  /** Reads header dimensions, rejects a decompression bomb, then decodes. null = skip. */
  private BufferedImage decodeWithBombGuard(byte[] bytes, Object attachmentId) throws Exception {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) {
        log.warn("no ImageIO reader for attachment={} — skipping thumbnail", attachmentId);
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(iis);
        long width = reader.getWidth(0);
        long height = reader.getHeight(0);
        if (exceedsDecodedBudget(width, height)) {
          log.error(
              "attachment={} declares {}x{} (> {} MiB decoded) — skipping thumbnail (bomb guard)",
              attachmentId,
              width,
              height,
              MAX_DECODED_BYTES / (1024 * 1024));
          return null;
        }
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  private byte[] toJpegThumbnail(BufferedImage source) throws Exception {
    int w = source.getWidth();
    int h = source.getHeight();
    double scale = (double) LONGEST_EDGE / Math.max(w, h);
    int nw = Math.max(1, (int) Math.round(w * scale));
    int nh = Math.max(1, (int) Math.round(h * scale));

    // TYPE_INT_RGB (no alpha — JPEG has none); white background for transparent sources.
    BufferedImage thumb = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = thumb.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setColor(java.awt.Color.WHITE);
      g.fillRect(0, 0, nw, nh);
      g.drawImage(source, 0, 0, nw, nh, null);
    } finally {
      g.dispose();
    }

    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(JPEG_QUALITY);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(thumb, null, null), param);
    } finally {
      writer.dispose();
    }
    return baos.toByteArray();
  }
}
