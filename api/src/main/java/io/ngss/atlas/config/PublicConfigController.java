package io.ngss.atlas.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated public-config endpoint (T-023). Reachable WITHOUT a token (see
 * SecurityConfig permit), so the SPA can decide whether to render flag-gated UI
 * (e.g. the watch toggle) before the user logs in. Returns ONLY the features
 * object — never anything sensitive.
 */
@RestController
@RequestMapping(value = "/api/config", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "config")
public class PublicConfigController {

  private final FeatureFlags featureFlags;

  public PublicConfigController(FeatureFlags featureFlags) {
    this.featureFlags = featureFlags;
  }

  @GetMapping("/public")
  @Operation(
      operationId = "getPublicConfig",
      summary = "Public feature flags (unauthenticated)",
      description = "Non-sensitive feature flags the SPA needs before login.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Public configuration",
        content = @Content(schema = @Schema(implementation = PublicConfigResponse.class)))
  })
  public PublicConfigResponse getPublicConfig() {
    return new PublicConfigResponse(new FeaturesDto(featureFlags.watchersEnabled()));
  }
}
