package io.ngss.atlas.watcher;

import io.ngss.atlas.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket-watcher endpoints (T-023). All take {@code @PathVariable UUID} and use
 * {@link CurrentUser#id()} for the caller (mirroring TicketController). When the
 * watchers feature is OFF, the service throws 404 on all three. The list endpoint
 * returns a BARE {@code List<UUID>} (precedent: listMembers/listLabels — watcher
 * sets are small and bounded, so no PagedResponse envelope).
 */
@RestController
@RequestMapping(value = "/api/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
// Tagged "tickets" (not "watchers") so codegen folds watch/unwatch/list into the
// generated TicketsService alongside the other /api/tickets operations.
@Tag(name = "tickets")
public class WatcherController {

  private final WatcherService watcherService;

  public WatcherController(WatcherService watcherService) {
    this.watcherService = watcherService;
  }

  @PutMapping("/{id}/watch")
  @Operation(
      operationId = "watchTicket",
      summary = "Watch a ticket (any member). Idempotent — a second call is a no-op.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Watching (created or already present)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(
        responseCode = "404",
        description = "Ticket not found, caller is not a member, or watchers disabled")
  })
  public ResponseEntity<Void> watchTicket(@PathVariable UUID id) {
    watcherService.watch(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/watch")
  @Operation(
      operationId = "unwatchTicket",
      summary = "Stop watching a ticket (any member). Idempotent — a second call is a no-op.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Not watching (removed or already absent)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(
        responseCode = "404",
        description = "Ticket not found, caller is not a member, or watchers disabled")
  })
  public ResponseEntity<Void> unwatchTicket(@PathVariable UUID id) {
    watcherService.unwatch(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/watchers")
  @Operation(
      operationId = "listTicketWatchers",
      summary = "List a ticket's watcher user ids, oldest-first (any member)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Watcher user ids (bare array)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(
        responseCode = "404",
        description = "Ticket not found, caller is not a member, or watchers disabled")
  })
  public List<UUID> listTicketWatchers(@PathVariable UUID id) {
    return watcherService.listWatcherIds(id);
  }
}
