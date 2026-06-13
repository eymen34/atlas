package io.ngss.atlas.link;

import io.ngss.atlas.link.dto.CreateLinkRequest;
import io.ngss.atlas.link.dto.LinkResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket-link endpoints (T-026). Create/list are ticket-scoped
 * ({@code /api/tickets/{id}/links}); delete is link-scoped ({@code /api/links/{id}}) —
 * so there is no class-level path, only the shared {@code produces}. Caller id comes
 * from {@link CurrentUser#id()}; all authz lives in {@link LinkService}.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "links")
public class LinkController {

  private final LinkService linkService;

  public LinkController(LinkService linkService) {
    this.linkService = linkService;
  }

  @PostMapping(value = "/api/tickets/{id}/links", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "createTicketLink",
      summary = "Link this ticket to another in the same project (any member)",
      description =
          "Accepts BLOCKS, DUPLICATES, or RELATES_TO. Persists the relation AND its inverse "
              + "(both rows in one transaction) and writes a LINK_ADDED activity row on both "
              + "tickets. Target must be in the same project. One relation per pair — a second "
              + "link between the same pair (either direction) → 409.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Link created",
        content = @Content(schema = @Schema(implementation = LinkResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Self-link, unknown/other-project key, or an inverse relation type"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member"),
    @ApiResponse(responseCode = "409", description = "A link already exists between these tickets")
  })
  public ResponseEntity<LinkResponse> createTicketLink(
      @PathVariable UUID id, @Valid @RequestBody CreateLinkRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(linkService.createLink(id, CurrentUser.id(), req));
  }

  @GetMapping("/api/tickets/{id}/links")
  @Operation(
      operationId = "listTicketLinks",
      summary = "List a ticket's outgoing links, newest first (any member)",
      description =
          "Bare array, each enriched with the target's ticketKey/title/status and a "
              + "targetDeleted flag (soft-deleted targets are kept, not filtered). Grouping by "
              + "relation is client-side.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Links listed"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<List<LinkResponse>> listTicketLinks(@PathVariable UUID id) {
    return ResponseEntity.ok(linkService.listLinks(id, CurrentUser.id()));
  }

  @DeleteMapping("/api/links/{id}")
  @Operation(
      operationId = "deleteTicketLink",
      summary = "Delete a link (any project member)",
      description =
          "Removes BOTH reciprocal rows in one transaction and writes a LINK_REMOVED activity "
              + "row on both tickets. Any member of the project may delete, regardless of who "
              + "created the link. A second delete of the same id → 404.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Link deleted"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Link not found or caller is not a member")
  })
  public ResponseEntity<Void> deleteTicketLink(@PathVariable UUID id) {
    linkService.deleteLink(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
