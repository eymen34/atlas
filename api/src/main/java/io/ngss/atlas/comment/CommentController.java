package io.ngss.atlas.comment;

import io.ngss.atlas.comment.dto.CommentResponse;
import io.ngss.atlas.comment.dto.CreateCommentRequest;
import io.ngss.atlas.comment.dto.UpdateCommentRequest;
import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comment endpoints (T-022). Create/list are ticket-scoped
 * ({@code /api/tickets/{id}/comments}); edit/delete are comment-scoped
 * ({@code /api/comments/{id}}) — so there is no class-level path, only the shared
 * {@code produces}. The caller id comes from {@link CurrentUser#id()} (mirroring
 * {@link io.ngss.atlas.ticket.TicketController}), NOT an
 * {@code @AuthenticationPrincipal} argument. Membership/authorship/admin checks live
 * in {@link CommentService}.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "comments")
public class CommentController {

  private final CommentService commentService;

  public CommentController(CommentService commentService) {
    this.commentService = commentService;
  }

  @PostMapping(value = "/api/tickets/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "createComment",
      summary = "Add a comment to a ticket (any member)",
      description =
          "Body is HTML (TipTap). @mentions are resolved SERVER-side against the "
              + "ticket's project membership; the client's mention metadata is ignored. "
              + "Writes a COMMENT_ADDED activity row atomically.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Comment created",
        content = @Content(schema = @Schema(implementation = CommentResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<CommentResponse> createComment(
      @PathVariable UUID id, @Valid @RequestBody CreateCommentRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(commentService.create(id, req, CurrentUser.id()));
  }

  @GetMapping("/api/tickets/{id}/comments")
  @Operation(
      operationId = "listComments",
      summary = "List a ticket's comments, newest first (any member)",
      description =
          "Paged (size clamped 1..100). Soft-deleted comments are returned with "
              + "body=null and deleted=true (server-redacted), keeping their place.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Comments listed (paged envelope)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<PagedResponse<CommentResponse>> listComments(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(commentService.list(id, page, size, CurrentUser.id()));
  }

  @PatchMapping(value = "/api/comments/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "updateComment",
      summary = "Edit a comment (author or project ADMIN)",
      description =
          "Replaces the full HTML body; mentions are re-derived server-side. Writes a "
              + "COMMENT_EDITED activity row. Non-author non-admin → 403.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Comment updated",
        content = @Content(schema = @Schema(implementation = CommentResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is neither the author nor an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Comment not found or caller is not a member")
  })
  public ResponseEntity<CommentResponse> updateComment(
      @PathVariable UUID id, @Valid @RequestBody UpdateCommentRequest req) {
    return ResponseEntity.ok(commentService.update(id, req, CurrentUser.id()));
  }

  @DeleteMapping("/api/comments/{id}")
  @Operation(
      operationId = "deleteComment",
      summary = "Soft-delete a comment (author or project ADMIN)",
      description =
          "Server-redacted soft delete: deleted_at is stamped, mention rows removed, "
              + "a COMMENT_DELETED activity row written. A second delete → 404.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Comment soft-deleted"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is neither the author nor an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Comment not found or already deleted")
  })
  public ResponseEntity<Void> deleteComment(@PathVariable UUID id) {
    commentService.softDelete(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
