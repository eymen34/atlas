package io.ngss.atlas.notification;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.notification.dto.NotificationResponse;
import io.ngss.atlas.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app notification endpoints (T-024). All caller-scoped — the user id comes ONLY
 * from {@link CurrentUser#id()} (SecurityContext), never a request param, so a
 * client cannot read or mutate another user's notifications. There is deliberately
 * NO unread-count endpoint (the badge uses the list with {@code unread=true&size=1}
 * and reads {@code total}). The mark endpoints are bodyless POSTs (no {@code
 * consumes}, so a no-body request is not a 415).
 */
@RestController
@RequestMapping(value = "/api/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "notifications")
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  @Operation(
      operationId = "listNotifications",
      summary = "List the caller's notifications, newest first",
      description =
          "Paged (size clamped 1..100). unread=true filters to unread only. Each row is "
              + "enriched with projectKey, ticketKey, ticketTitle, actorDisplayName.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Notifications (paged envelope)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
  })
  public PagedResponse<NotificationResponse> listNotifications(
      @RequestParam(required = false) Boolean unread,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return notificationService.list(CurrentUser.id(), unread, page, size);
  }

  @PostMapping("/{id}/read")
  @Operation(
      operationId = "markNotificationRead",
      summary = "Mark one notification read (idempotent; caller-scoped)")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Marked read (or already read)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Notification not found or not the caller's")
  })
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markNotificationRead(@PathVariable UUID id) {
    notificationService.markRead(CurrentUser.id(), id);
  }

  @PostMapping("/read-all")
  @Operation(
      operationId = "markAllNotificationsRead",
      summary = "Mark all the caller's notifications read (idempotent)")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "All marked read"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
  })
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAllNotificationsRead() {
    notificationService.markAllRead(CurrentUser.id());
  }
}
