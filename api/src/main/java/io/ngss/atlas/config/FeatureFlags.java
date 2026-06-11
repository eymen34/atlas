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

  public FeatureFlags(@Value("${app.feature.watchers.enabled:true}") boolean watchersEnabled) {
    this.watchersEnabled = watchersEnabled;
  }

  public boolean watchersEnabled() {
    return watchersEnabled;
  }
}
