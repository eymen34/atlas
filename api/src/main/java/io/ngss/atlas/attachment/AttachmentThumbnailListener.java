package io.ngss.atlas.attachment;

import io.ngss.atlas.config.FeatureFlags;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Generates a JPEG thumbnail for a freshly-finalized image attachment (T-025).
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} +
 * {@code try/catch + ERROR log} (after_commit_requires_new): the originating finalize
 * has already committed, so a thumbnail failure NEVER rolls it back — thumbnail loss
 * is the accepted tradeoff. Internally feature-flagged ({@code inlineThumbnailsEnabled},
 * swapped for an outbox worker in T-029).
 *
 * <p>This worker is the ONE documented bounded exception to "the app never holds file
 * bytes" — it GETs the object into memory (bounded by ATTACHMENT_MAX_SIZE_BYTES) and,
 * BEFORE decoding pixels, reads the image header dimensions and rejects a
 * decompression bomb ({@code w*h*4 > 64 MiB}). JPEG, not webp: the JVM has no webp
 * encoder (and no webp decoder without TwelveMonkeys, so webp uploads simply get no
 * thumbnail).
 */
@Component
public class AttachmentThumbnailListener {

  private static final Logger log = LoggerFactory.getLogger(AttachmentThumbnailListener.class);
  private static final int LONGEST_EDGE = 256;
  private static final long MAX_DECODED_BYTES = 64L * 1024 * 1024; // 64 MiB
  private static final float JPEG_QUALITY = 0.85f;

  private final AttachmentRepository attachmentRepository;
  private final ObjectStorageProperties props;
  private final FeatureFlags featureFlags;
  private final S3Client s3Client;

  public AttachmentThumbnailListener(
      AttachmentRepository attachmentRepository,
      ObjectStorageProperties props,
      FeatureFlags featureFlags,
      @Lazy S3Client s3Client) {
    this.attachmentRepository = attachmentRepository;
    this.props = props;
    this.featureFlags = featureFlags;
    this.s3Client = s3Client;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onAttachmentFinalized(AttachmentFinalizedEvent e) {
    if (!featureFlags.inlineThumbnailsEnabled()) {
      return;
    }
    if (e.contentType() == null || !e.contentType().toLowerCase().startsWith("image/")) {
      return; // only images get thumbnails
    }
    try {
      byte[] original =
          s3Client
              .getObjectAsBytes(
                  GetObjectRequest.builder().bucket(props.bucket()).key(e.objectKey()).build())
              .asByteArray();

      BufferedImage source = decodeWithBombGuard(original, e.attachmentId());
      if (source == null) {
        return; // unreadable, unknown format, or bomb — already logged
      }

      byte[] jpeg = toJpegThumbnail(source);
      String thumbnailKey = "thumbnails/" + e.attachmentId() + ".jpg";
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(props.bucket())
              .key(thumbnailKey)
              .contentType("image/jpeg")
              .build(),
          RequestBody.fromBytes(jpeg));

      attachmentRepository
          .findByIdAndDeletedAtIsNull(e.attachmentId())
          .ifPresent(
              a -> {
                a.attachThumbnail(thumbnailKey);
                attachmentRepository.save(a);
              });
    } catch (Exception ex) {
      // Thumbnail loss is acceptable; the attachment is already READY. Never propagate.
      log.error("thumbnail generation failed for attachment={}", e.attachmentId(), ex);
    }
  }

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
        if (width * height * 4L > MAX_DECODED_BYTES) {
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
