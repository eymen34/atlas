package io.ngss.atlas.outbox;

import io.ngss.atlas.attachment.ObjectStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.ObjectMapper;

/**
 * Removes the S3 object(s) behind a soft-deleted attachment for an
 * {@link OutboxKind#ATTACHMENT_DELETE_OBJECT} row (T-029). Uses the INTERNAL {@link S3Client}
 * (server-side ops), NOT the {@code S3Presigner} — same {@code @Lazy} injection as
 * {@code AttachmentThumbnailListener} so the stage-3 no-DB AppCDS boot never constructs it. A
 * missing key is treated as success (idempotent); any other S3 error propagates → backoff.
 */
@Component
public class AttachmentDeleteHandler implements OutboxHandler {

  private static final Logger log = LoggerFactory.getLogger(AttachmentDeleteHandler.class);

  private final S3Client s3Client;
  private final ObjectStorageProperties props;
  private final ObjectMapper objectMapper;

  public AttachmentDeleteHandler(
      @Lazy S3Client s3Client, ObjectStorageProperties props, ObjectMapper objectMapper) {
    this.s3Client = s3Client;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  @Override
  public OutboxKind kind() {
    return OutboxKind.ATTACHMENT_DELETE_OBJECT;
  }

  @Override
  public void handle(OutboxRow row) throws Exception {
    AttachmentDeletePayload payload =
        objectMapper.treeToValue(row.payload(), AttachmentDeletePayload.class);
    deleteKey(payload.objectKey());
    if (payload.thumbnailObjectKey() != null) {
      deleteKey(payload.thumbnailObjectKey());
    }
  }

  private void deleteKey(String key) {
    try {
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
    } catch (NoSuchKeyException alreadyGone) {
      // Object already absent — delete is idempotent, treat as success.
      log.info("attachment object already absent (idempotent delete): {}", key);
    }
    // Any other S3Exception propagates → the drain backs off and retries.
  }
}
