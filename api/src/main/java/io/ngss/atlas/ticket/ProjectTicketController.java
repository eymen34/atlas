package io.ngss.atlas.ticket;

import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.security.CurrentUser;
import io.ngss.atlas.ticket.dto.CreateTicketRequest;
import io.ngss.atlas.ticket.dto.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-scoped ticket endpoints (T-017): create and list within a project. Both
 * require membership (non-member → 404, existence-leak prevention); authorization
 * is enforced in {@link TicketService} via ProjectAccessGuard. Ticket-scoped
 * operations (get/patch/transition/delete) live on {@link TicketController}.
 *
 * <p>Class-level {@code produces=application/json} keeps springdoc content types
 * concrete; the POST adds {@code consumes=application/json}.
 */
@RestController
@RequestMapping(value = "/api/projects/{id}/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "tickets")
public class ProjectTicketController {

  private final TicketService ticketService;

  public ProjectTicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "createTicket",
      summary = "Create a ticket in a project (any member); number is auto-assigned")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Ticket created",
        content = @Content(schema = @Schema(implementation = TicketResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
  })
  public ResponseEntity<TicketResponse> create(
      @PathVariable UUID id, @Valid @RequestBody CreateTicketRequest req) {
    TicketResponse created = ticketService.create(id, req, CurrentUser.id());
    return ResponseEntity.created(URI.create("/api/tickets/" + created.id())).body(created);
  }

  @GetMapping
  @Operation(
      operationId = "listTickets",
      summary = "List a project's live tickets (any member)",
      description =
          "Default sort is updated_at DESC. Optional filters status / assigneeId / priority "
              + "compose. The q (search) and label params are accepted but currently ignored "
              + "(reserved for T-018 / T-028).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Tickets listed",
        content =
            @Content(array = @ArraySchema(schema = @Schema(implementation = TicketResponse.class)))),
    @ApiResponse(responseCode = "400", description = "Invalid filter value"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
  })
  public ResponseEntity<List<TicketResponse>> list(
      @PathVariable UUID id,
      @RequestParam(required = false) TicketStatus status,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(required = false) TicketPriority priority,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String label) {
    return ResponseEntity.ok(ticketService.list(id, status, assigneeId, priority, q, label));
  }
}
