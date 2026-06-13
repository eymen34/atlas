package io.ngss.atlas.attachment;

import io.ngss.atlas.attachment.dto.AttachmentResponse;
import io.ngss.atlas.attachment.dto.DownloadUrlResponse;
import io.ngss.atlas.attachment.dto.InitUploadRequest;
import io.ngss.atlas.attachment.dto.InitUploadResponse;
import io.ngss.atlas.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attachment endpoints (T-025). Init/list are ticket-scoped
 * ({@code /api/tickets/{id}/attachments[...]}); finalize/download/delete are
 * attachment-scoped ({@code /api/attachments/{id}[...]}) — so there is no class-level
 * path, only the shared {@code produces}. The bytes never touch this controller:
 * init returns a presigned PUT, download returns a presigned GET. The caller id comes
 * from {@link CurrentUser#id()}; all authz lives in {@link AttachmentService}. The
 * bodyless POST (finalize) declares no {@code consumes} so a no-body request is not a
 * 415.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "attachments")
public class AttachmentController {

  private final AttachmentService attachmentService;

  public AttachmentController(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @PostMapping(
      value = "/api/tickets/{id}/attachments/init",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "initAttachmentUpload",
      summary = "Begin an attachment upload — returns a presigned PUT URL (any member)",
      description =
          "Validates the content-type allowlist and the CLAIMED size against "
              + "ATTACHMENT_MAX_SIZE_BYTES, creates a PENDING row, and returns a 10-minute "
              + "presigned PUT URL (Content-Type signed). The browser PUTs the bytes directly "
              + "to S3; the real size gate is the finalize HEAD (a presigned PUT cannot enforce "
              + "a max size).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Upload initialized",
        content = @Content(schema = @Schema(implementation = InitUploadResponse.class))),
    @ApiResponse(responseCode = "400", description = "Disallowed content type or oversize"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<InitUploadResponse> initAttachmentUpload(
      @PathVariable UUID id, @Valid @RequestBody InitUploadRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(attachmentService.init(id, req, CurrentUser.id()));
  }

  @PostMapping("/api/attachments/{id}/finalize")
  @Operation(
      operationId = "finalizeAttachment",
      summary = "Finalize an upload — server verifies the object (uploader only)",
      description =
          "HEADs the uploaded object and verifies its actual size and content-type match "
              + "the declared values; success → READY + ATTACHMENT_ADDED activity (idempotent: "
              + "already-READY → 204). Mismatch or missing object → FAILED + 400 (retry allowed). "
              + "Only the uploader may finalize; a foreign PENDING row → 404.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Finalized (or already finalized)"),
    @ApiResponse(responseCode = "400", description = "Object missing or size/content-type mismatch"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Attachment not found or not the uploader")
  })
  @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
  public void finalizeAttachment(@PathVariable UUID id) {
    attachmentService.finalizeUpload(id, CurrentUser.id());
  }

  @GetMapping("/api/tickets/{id}/attachments")
  @Operation(
      operationId = "listTicketAttachments",
      summary = "List a ticket's READY attachments, newest first (any member)",
      description = "Bare array of READY, non-deleted attachments; each carries a hasThumbnail flag.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Attachments listed"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<List<AttachmentResponse>> listTicketAttachments(@PathVariable UUID id) {
    return ResponseEntity.ok(attachmentService.list(id, CurrentUser.id()));
  }

  @GetMapping("/api/attachments/{id}/download-url")
  @Operation(
      operationId = "getAttachmentDownloadUrl",
      summary = "Get a short-lived presigned GET URL (any member)",
      description =
          "Returns a 5-minute presigned GET URL signed against the PUBLIC endpoint. "
              + "thumbnail=true returns the thumbnail's URL (404 if the attachment has none). "
              + "Never 302-redirects — the JSON API stays JSON.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Presigned URL",
        content = @Content(schema = @Schema(implementation = DownloadUrlResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(
        responseCode = "404",
        description = "Attachment not found, caller is not a member, or no thumbnail")
  })
  public ResponseEntity<DownloadUrlResponse> getAttachmentDownloadUrl(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean thumbnail) {
    return ResponseEntity.ok(attachmentService.downloadUrl(id, thumbnail, CurrentUser.id()));
  }

  @DeleteMapping("/api/attachments/{id}")
  @Operation(
      operationId = "deleteAttachment",
      summary = "Soft-delete an attachment (uploader or project ADMIN)",
      description =
          "Stamps deleted_at and writes an ATTACHMENT_REMOVED activity row; the S3 object "
              + "removal is deferred to the T-029 outbox sweeper. A second delete → 404.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Attachment soft-deleted"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is neither the uploader nor an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Attachment not found or already deleted")
  })
  public ResponseEntity<Void> deleteAttachment(@PathVariable UUID id) {
    attachmentService.delete(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
