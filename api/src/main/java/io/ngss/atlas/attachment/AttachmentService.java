package io.ngss.atlas.attachment;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.activity.payload.AttachmentAddedPayload;
import io.ngss.atlas.activity.payload.AttachmentRemovedPayload;
import io.ngss.atlas.attachment.dto.AttachmentResponse;
import io.ngss.atlas.attachment.dto.DownloadUrlResponse;
import io.ngss.atlas.attachment.dto.FinalizeResponse;
import io.ngss.atlas.attachment.dto.InitUploadRequest;
import io.ngss.atlas.attachment.dto.InitUploadResponse;
import io.ngss.atlas.config.FeatureFlags;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.Attachment;
import io.ngss.atlas.domain.AttachmentStatus;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.outbox.AttachmentDeletePayload;
import io.ngss.atlas.outbox.OutboxKind;
import io.ngss.atlas.outbox.OutboxRepository;
import io.ngss.atlas.project.ForbiddenProjectAccessException;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.TicketNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Application service for the Attachment aggregate (T-025). The file bytes NEVER
 * traverse the app on the HTTP path: init issues a presigned PUT (browser → S3
 * directly), finalize HEADs the uploaded object server-side to verify it, and
 * download issues a presigned GET. Authorization mirrors CommentService: load the
 * ticket, guard {@code ticket.projectId} (non-member → 404); finalize is
 * uploader-only; delete is uploader-OR-admin.
 *
 * <p>The two S3 beans are injected {@code @Lazy} so the Dockerfile stage-3 no-DB
 * AppCDS boot never constructs them (appcds_boot_safety).
 */
@Service
public class AttachmentService {

  /**
   * Accepted upload content types. A presigned PUT signs the Content-Type, so the
   * stored object's type matches what was claimed here; finalize re-verifies via HEAD
   * (defense for any direct-PUT path).
   */
  static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of(
          "image/png",
          "image/jpeg",
          "image/gif",
          "image/webp",
          "application/pdf",
          "text/plain",
          "text/markdown",
          "text/csv",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation");

  private static final Duration PUT_TTL = Duration.ofMinutes(10);
  private static final Duration GET_TTL = Duration.ofMinutes(5);

  private final AttachmentRepository attachmentRepository;
  private final TicketRepository ticketRepository;
  private final ProjectAccessGuard guard;
  private final ActivityEventWriter activityWriter;
  private final ObjectStorageProperties props;
  private final FeatureFlags featureFlags;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  public AttachmentService(
      AttachmentRepository attachmentRepository,
      TicketRepository ticketRepository,
      ProjectAccessGuard guard,
      ActivityEventWriter activityWriter,
      ObjectStorageProperties props,
      FeatureFlags featureFlags,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      @Lazy S3Client s3Client,
      @Lazy S3Presigner s3Presigner) {
    this.attachmentRepository = attachmentRepository;
    this.ticketRepository = ticketRepository;
    this.guard = guard;
    this.activityWriter = activityWriter;
    this.props = props;
    this.featureFlags = featureFlags;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
  }

  @Transactional
  public InitUploadResponse init(UUID ticketId, InitUploadRequest req, UUID callerId) {
    Ticket ticket = loadTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    if (!ALLOWED_CONTENT_TYPES.contains(req.contentType())) {
      throw new AttachmentValidationException("unsupported content type: " + req.contentType());
    }
    if (req.sizeBytes() > props.maxSizeBytes()) {
      throw new AttachmentValidationException(
          "file exceeds the maximum allowed size of " + props.maxSizeBytes() + " bytes");
    }

    UUID id = UUID.randomUUID();
    String objectKey = "tickets/" + ticketId + "/" + id + "/" + sanitizeFilename(req.filename());
    Instant now = Instant.now();
    Attachment attachment =
        new Attachment(
            id,
            ticketId,
            callerId,
            objectKey,
            req.filename(),
            req.contentType(),
            req.sizeBytes(),
            AttachmentStatus.PENDING,
            now);
    attachmentRepository.save(attachment);

    String uploadUrl = presignPut(objectKey, req.contentType());
    return new InitUploadResponse(id, uploadUrl, Map.of("Content-Type", req.contentType()));
  }

  @Transactional
  public FinalizeResponse finalizeUpload(UUID attachmentId, UUID callerId) {
    Attachment attachment = loadLive(attachmentId);
    Ticket ticket = loadTicket(attachment.getTicketId());
    guard.requireMember(ticket.getProjectId());
    // Uploader-only: a member who is not the uploader cannot finalize a foreign
    // PENDING row → uniform 404 (no ownership leak). These guards run BEFORE any state
    // mutation, so throwing here rolls back nothing.
    if (!attachment.getUploadedBy().equals(callerId)) {
      throw new AttachmentNotFoundException(attachmentId);
    }
    if (attachment.getStatus() == AttachmentStatus.READY) {
      return FinalizeResponse.ready(); // idempotent no-op (already finalized)
    }

    // A mismatch is a STATE-MACHINE outcome, NOT an exception: we must COMMIT the
    // FAILED status write. Throwing an exception out of this @Transactional method
    // marks the tx rollback-only and silently discards markFailed() → the row stays
    // PENDING (jpa_rollback_only_trap). So every mismatch path returns FAILED + 200.
    HeadObjectResponse head;
    try {
      head =
          s3Client.headObject(
              HeadObjectRequest.builder()
                  .bucket(props.bucket())
                  .key(attachment.getObjectKey())
                  .build());
    } catch (S3Exception e) {
      return markFailed(attachment, "object_missing");
    }

    if (head.contentLength() == null || head.contentLength() != attachment.getSizeBytes()) {
      return markFailed(attachment, "size_mismatch");
    }
    if (!contentTypeMatches(head.contentType(), attachment.getContentType())) {
      return markFailed(attachment, "content_type_mismatch");
    }

    Instant now = Instant.now();
    attachment.markReady(now);
    attachmentRepository.save(attachment);
    activityWriter.record(
        attachment.getTicketId(),
        callerId,
        ActivityEventType.ATTACHMENT_ADDED,
        new AttachmentAddedPayload(attachment.getId()),
        now);
    // T-040: enqueue thumbnail generation in the SAME finalize transaction (transactional outbox),
    // replacing the old AFTER_COMMIT AttachmentThumbnailListener. image/* only; the drain handler
    // generates the JPEG with retry/backoff. Flag OFF → clean no-op (zero rows). The payload is the
    // attachment id alone (the handler re-loads to read object_key) — same enqueue API as the
    // ATTACHMENT_DELETE_OBJECT path. The idempotent already-READY early return above prevents a
    // double-enqueue on a retried finalize.
    if (featureFlags.inlineThumbnailsEnabled()
        && attachment.getContentType().toLowerCase(Locale.ROOT).startsWith("image/")) {
      outboxRepository.enqueue(
          OutboxKind.ATTACHMENT_THUMBNAIL,
          objectMapper.valueToTree(AttachmentThumbnailPayload.of(attachment.getId())));
    }
    return FinalizeResponse.ready();
  }

  private FinalizeResponse markFailed(Attachment attachment, String reason) {
    attachment.markFailed();
    attachmentRepository.save(attachment); // committed (no exception) → status persists
    return FinalizeResponse.failed(reason);
  }

  @Transactional(readOnly = true)
  public List<AttachmentResponse> list(UUID ticketId, UUID callerId) {
    Ticket ticket = loadTicket(ticketId);
    guard.requireMember(ticket.getProjectId());
    return attachmentRepository
        .findByTicketIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            ticketId, AttachmentStatus.READY)
        .stream()
        .map(AttachmentResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public DownloadUrlResponse downloadUrl(UUID attachmentId, boolean thumbnail, UUID callerId) {
    Attachment attachment = loadLive(attachmentId);
    Ticket ticket = loadTicket(attachment.getTicketId());
    guard.requireMember(ticket.getProjectId());

    String key;
    if (thumbnail) {
      key = attachment.getThumbnailObjectKey();
      if (key == null) {
        throw new AttachmentNotFoundException(attachmentId); // no thumbnail for this attachment
      }
    } else {
      key = attachment.getObjectKey();
    }
    return new DownloadUrlResponse(presignGet(key));
  }

  @Transactional
  public void delete(UUID attachmentId, UUID callerId) {
    // Already-deleted → loadLive empty → 404 (idempotency boundary).
    Attachment attachment = loadLive(attachmentId);
    Ticket ticket = loadTicket(attachment.getTicketId());
    guard.requireMember(ticket.getProjectId());
    if (!attachment.getUploadedBy().equals(callerId) && !guard.isAdmin(ticket.getProjectId())) {
      throw new ForbiddenProjectAccessException(ticket.getProjectId());
    }

    Instant now = Instant.now();
    attachment.softDelete(now);
    attachmentRepository.save(attachment);
    // T-029: enqueue the S3 object removal in the SAME transaction as the soft-delete, so the
    // outbox row and the deleted_at commit atomically. The drain handler removes the object key
    // (and the thumbnail key, if any) — replacing the old "deferred sweeper" TODO.
    outboxRepository.enqueue(
        OutboxKind.ATTACHMENT_DELETE_OBJECT,
        objectMapper.valueToTree(
            new AttachmentDeletePayload(
                attachment.getObjectKey(), attachment.getThumbnailObjectKey())));
    activityWriter.record(
        attachment.getTicketId(),
        callerId,
        ActivityEventType.ATTACHMENT_REMOVED,
        new AttachmentRemovedPayload(attachment.getId()),
        now);
  }

  /**
   * System-context soft-delete for the T-053 maintenance expiry sweep — NO actor, NO project
   * guard (the caller is the internal cron). Reuses the SAME single
   * {@code ATTACHMENT_DELETE_OBJECT} enqueue as {@link #delete}; deliberately records NO
   * {@code ATTACHMENT_REMOVED} activity, because an expired never-finalized PENDING upload was
   * never visible (it never produced an {@code ATTACHMENT_ADDED}).
   *
   * <p>{@code @Transactional} (REQUIRED): invoked per-row from {@code MaintenanceService}'s
   * {@code NEVER} context, so each call opens its OWN transaction — the {@code deleted_at} write
   * and the outbox enqueue commit together or roll back together. Idempotent: an
   * already-soft-deleted (or absent) row is a silent no-op, so a concurrent sweep never
   * double-enqueues.
   */
  @Transactional
  public void softDeleteSystem(UUID id) {
    Attachment attachment = attachmentRepository.findByIdAndDeletedAtIsNull(id).orElse(null);
    if (attachment == null) {
      return; // already soft-deleted or gone — no-op, no second enqueue
    }
    attachment.softDelete(Instant.now());
    attachmentRepository.save(attachment);
    outboxRepository.enqueue(
        OutboxKind.ATTACHMENT_DELETE_OBJECT,
        objectMapper.valueToTree(
            new AttachmentDeletePayload(
                attachment.getObjectKey(), attachment.getThumbnailObjectKey())));
  }

  // ───────────────────────── helpers ─────────────────────────

  private String presignPut(String objectKey, String contentType) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(objectKey)
            .contentType(contentType)
            .build();
    return s3Presigner
        .presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(PUT_TTL)
                .putObjectRequest(put)
                .build())
        .url()
        .toString();
  }

  private String presignGet(String objectKey) {
    GetObjectRequest get =
        GetObjectRequest.builder().bucket(props.bucket()).key(objectKey).build();
    return s3Presigner
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(GET_TTL)
                .getObjectRequest(get)
                .build())
        .url()
        .toString();
  }

  /** Compares only the media type (ignoring any {@code ;charset=…} parameter), case-insensitively. */
  private static boolean contentTypeMatches(String actual, String declared) {
    if (actual == null) {
      return false;
    }
    return baseType(actual).equals(baseType(declared));
  }

  private static String baseType(String ct) {
    int semi = ct.indexOf(';');
    return (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
  }

  /** Keep only filename-safe chars so the object key is clean; never trust raw input. */
  static String sanitizeFilename(String filename) {
    if (filename == null) {
      return "file";
    }
    String base = filename.replaceAll("[^A-Za-z0-9._-]", "_");
    if (base.length() > 200) {
      base = base.substring(base.length() - 200);
    }
    // Fall back when nothing meaningful survives (empty, or only separators like "///").
    return base.chars().anyMatch(Character::isLetterOrDigit) ? base : "file";
  }

  private Ticket loadTicket(UUID ticketId) {
    return ticketRepository
        .findById(ticketId)
        .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + ticketId));
  }

  private Attachment loadLive(UUID attachmentId) {
    return attachmentRepository
        .findByIdAndDeletedAtIsNull(attachmentId)
        .orElseThrow(() -> new AttachmentNotFoundException(attachmentId));
  }
}
