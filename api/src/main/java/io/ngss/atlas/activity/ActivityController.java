package io.ngss.atlas.activity;

import io.ngss.atlas.activity.dto.ActivityEventResponse;
import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.ActivityEvent;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.TicketNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Read endpoint for a ticket's activity log (T-019). Newest-first, paginated. The
 * ticket is loaded first (404 if missing/soft-deleted) and membership is enforced
 * via {@link ProjectAccessGuard} (non-member → 404, existence-leak prevention),
 * exactly like the other ticket-scoped reads.
 *
 * <p>Shares the {@code /api/tickets} base with {@code TicketController}; the
 * {@code /{id}/activity} sub-path is two segments deep, so it never collides with
 * {@code TicketController}'s {@code /{idOrKey}} single-segment routes.
 */
@RestController
@RequestMapping(value = "/api/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "activity")
public class ActivityController {

  private static final int MAX_PAGE_SIZE = 100;

  private final TicketRepository ticketRepository;
  private final ActivityEventRepository activityRepository;
  private final ProjectAccessGuard guard;
  private final ObjectMapper objectMapper;

  public ActivityController(
      TicketRepository ticketRepository,
      ActivityEventRepository activityRepository,
      ProjectAccessGuard guard,
      ObjectMapper objectMapper) {
    this.ticketRepository = ticketRepository;
    this.activityRepository = activityRepository;
    this.guard = guard;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/{id}/activity")
  @Operation(
      operationId = "listTicketActivity",
      summary = "List a ticket's activity log, newest first (any member)",
      description =
          "Paged: size is clamped to 1..100, page to >= 0. Items are ordered created_at DESC. "
              + "Each item's payload is a structured JSON object whose shape depends on eventType.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Activity listed (paged envelope)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Ticket not found or caller is not a member")
  })
  public ResponseEntity<PagedResponse<ActivityEventResponse>> listTicketActivity(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    int clampedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    int safePage = Math.max(0, page);

    Ticket ticket =
        ticketRepository
            .findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + id));
    guard.requireMember(ticket.getProjectId());

    Page<ActivityEvent> pageResult =
        activityRepository.findByTicketIdOrderByCreatedAtDesc(
            id, PageRequest.of(safePage, clampedSize));
    return ResponseEntity.ok(
        PagedResponse.from(pageResult, ev -> ActivityEventResponse.from(ev, objectMapper)));
  }
}
