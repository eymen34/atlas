package io.ngss.atlas.ticket;

import io.ngss.atlas.security.CurrentUser;
import io.ngss.atlas.ticket.dto.TicketResponse;
import io.ngss.atlas.ticket.dto.TransitionRequest;
import io.ngss.atlas.ticket.dto.UpdateTicketRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket-scoped endpoints (T-017): fetch by id-or-key, PATCH, transition, and
 * (ADMIN-only) soft-delete. There is no projectId in these URLs, so each operation
 * LOADS the ticket first and then enforces project membership on
 * {@code ticket.projectId} (two-step). Non-members get 404; DELETE additionally
 * requires ADMIN (403 for member-non-admin). Project-scoped create/list live on
 * {@link ProjectTicketController}.
 */
@RestController
@RequestMapping(value = "/api/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "tickets")
public class TicketController {

  private final TicketService ticketService;

  public TicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @GetMapping("/{idOrKey}")
  @Operation(
      operationId = "getTicket",
      summary = "Fetch a single ticket by UUID id or display key (e.g. ENG-42)",
      description =
          "A segment matching ^[A-Z][A-Z0-9]{1,9}-\\d+$ is treated as a project-key/number "
              + "display key; otherwise it is parsed as a UUID. Returns 404 if no live ticket the "
              + "caller can see matches.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Ticket found",
        content = @Content(schema = @Schema(implementation = TicketResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<TicketResponse> get(@PathVariable String idOrKey) {
    return ResponseEntity.ok(ticketService.getByIdOrKey(idOrKey));
  }

  @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "updateTicket",
      summary = "Partially update a ticket (any member)",
      description =
          "Null/absent fields are left unchanged; an explicit empty-string description clears it. "
              + "A present-but-blank title is a 400. Status is NOT changed here (use the transition "
              + "endpoint). updatedAt always advances; createdAt and number are never changed.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Ticket updated",
        content = @Content(schema = @Schema(implementation = TicketResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<TicketResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateTicketRequest req) {
    return ResponseEntity.ok(ticketService.update(id, req));
  }

  @PostMapping(value = "/{id}/transition", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "transitionTicket",
      summary = "Transition a ticket's status (any member)",
      description =
          "Any status may transition to any other (MVP — no state-machine). A transition to the "
              + "current status is a 200 no-op and publishes no event.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Ticket transitioned (or no-op if already in that status)",
        content = @Content(schema = @Schema(implementation = TicketResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<TicketResponse> transition(
      @PathVariable UUID id, @Valid @RequestBody TransitionRequest req) {
    return ResponseEntity.ok(ticketService.transition(id, req, CurrentUser.id()));
  }

  @DeleteMapping("/{id}")
  @Operation(operationId = "deleteTicket", summary = "Soft-delete a ticket (ADMIN only)")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Ticket soft-deleted"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is a member but not an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    ticketService.softDelete(id);
    return ResponseEntity.noContent().build();
  }
}
