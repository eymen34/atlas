package io.ngss.atlas.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime feature flags (T-023). Backed by `:default` SpEL literals so the bean
 * constructs cleanly during the Dockerfile stage-3 no-DB AppCDS boot with no env
 * present (appcds_boot_safety) — a primitive boolean, no external resource, no lazy
 * init needed.
 */
@Component
public class FeatureFlags {

  private final boolean watchersEnabled;
  private final boolean inlineThumbnailsEnabled;

  public FeatureFlags(
      @Value("${app.feature.watchers.enabled:true}") boolean watchersEnabled,
      @Value("${app.feature.inline-thumbnails.enabled:true}") boolean inlineThumbnailsEnabled) {
    this.watchersEnabled = watchersEnabled;
    this.inlineThumbnailsEnabled = inlineThumbnailsEnabled;
  }

  public boolean watchersEnabled() {
    return watchersEnabled;
  }

  /**
   * T-025: INTERNAL flag (NOT exposed via /api/config/public — it swaps server-side
   * thumbnail behavior, no UI dependency). Off → finalize still succeeds, just no
   * thumbnail. Swapped for outbox-driven generation in T-029.
   */
  public boolean inlineThumbnailsEnabled() {
    return inlineThumbnailsEnabled;
  }
}
