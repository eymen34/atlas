package io.ngss.atlas.ticket;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import io.ngss.atlas.security.CurrentUser;
import io.ngss.atlas.ticket.dto.CreateTicketRequest;
import io.ngss.atlas.ticket.dto.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
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
 * Project-scoped ticket endpoints (T-017): create and list within a project (T-018
 * rebuilt list with multi-valued filters + offset pagination). Both require
 * membership (non-member → 404). Ticket-scoped operations live on
 * {@link TicketController}.
 */
@RestController
@RequestMapping(value = "/api/projects/{id}/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "tickets")
public class ProjectTicketController {

  private static final int MAX_PAGE_SIZE = 100;

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
      operationId = "listProjectTickets",
      summary = "List a project's live tickets (any member), filtered and paged",
      description =
          "status and priority are multi-valued (OR within each field). assigneeId is a single "
              + "value: a UUID, or the literal 'unassigned' for tickets with no assignee. label is "
              + "multi-valued with AND semantics — a ticket must carry EVERY requested label. q "
              + "(search) is accepted but currently ignored (T-018 out of scope). Results are "
              + "sorted updated_at DESC (id ASC tiebreaker) and paged: size is clamped to 1..100, "
              + "page to >= 0.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Tickets listed (paged envelope)"),
    @ApiResponse(responseCode = "400", description = "Invalid filter or query parameter"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
  })
  public ResponseEntity<PagedResponse<TicketResponse>> list(
      @PathVariable UUID id,
      @RequestParam(required = false) List<TicketStatus> status,
      @RequestParam(required = false) List<TicketPriority> priority,
      @RequestParam(required = false) String assigneeId,
      @RequestParam(required = false) List<UUID> label,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    int clampedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    int clampedPage = Math.max(0, page);
    return ResponseEntity.ok(
        ticketService.list(id, status, priority, assigneeId, label, q, clampedPage, clampedSize));
  }
}
