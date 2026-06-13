package io.ngss.atlas.outbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, shared-secret-gated maintenance endpoints (T-029). Lives OUTSIDE {@code /api/**};
 * access is granted by {@link io.ngss.atlas.security.InternalSecretFilter} (ROLE_INTERNAL on a
 * valid {@code X-Internal-Secret}) — no JWT. Driven by an external cron (see {@code deploy/cron/}),
 * never an in-process scheduler (realtime/background_work: no scheduling annotations anywhere in
 * production sources).
 */
@RestController
@RequestMapping(value = "/internal/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalTasksController {

  /** One drain pass claims at most this many due rows (FOR UPDATE SKIP LOCKED batch). */
  private static final int DRAIN_BATCH_SIZE = 50;

  private final OutboxDrainService drainService;

  public InternalTasksController(OutboxDrainService drainService) {
    this.drainService = drainService;
  }

  @PostMapping("/drain-outbox")
  @Operation(
      operationId = "drainOutbox",
      summary = "Drain a batch of due outbox rows (internal; X-Internal-Secret required)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Drain summary (processed/succeeded/failed/retried)"),
    @ApiResponse(responseCode = "403", description = "Missing or invalid internal secret")
  })
  public DrainResult drainOutbox() {
    return drainService.drain(DRAIN_BATCH_SIZE);
  }
}
