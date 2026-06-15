package io.ngss.atlas.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refresh-cookie configuration (T-048). Constructor-{@code @Value} injection with safe defaults —
 * same AppCDS-safe shape as {@code FeatureFlags}/{@code ObjectStorageProperties} (no
 * {@code @ConfigurationProperties}/scan plumbing, no validation in the constructor), so the bean
 * constructs cleanly during the Dockerfile stage-3 no-DB boot.
 *
 * <p>{@link #secure} defaults TRUE (production); dev/test set {@code app.auth.cookie.secure=false}
 * (env {@code APP_AUTH_COOKIE_SECURE} / a test {@code @TestPropertySource}) because there is no TLS
 * on localhost. {@link #maxAgeDays} defaults to the existing {@code REFRESH_TOKEN_TTL_DAYS} so the
 * cookie's Max-Age tracks the refresh-token TTL by construction.
 *
 * <p>{@link #name} is documentation/config only — the authoritative cookie name for both
 * {@code Set-Cookie} and {@code @CookieValue} reads is the compile-time constant
 * {@link AuthCookieFactory#COOKIE_NAME}.
 */
@Component
public class CookieProperties {

  private final boolean secure;
  private final String name;
  private final String path;
  private final String sameSite;
  private final long maxAgeDays;

  public CookieProperties(
      @Value("${app.auth.cookie.secure:true}") boolean secure,
      @Value("${app.auth.cookie.name:atlas_refresh}") String name,
      @Value("${app.auth.cookie.path:/api/auth}") String path,
      @Value("${app.auth.cookie.same-site:Lax}") String sameSite,
      @Value("${app.auth.cookie.max-age-days:${REFRESH_TOKEN_TTL_DAYS:30}}") long maxAgeDays) {
    this.secure = secure;
    this.name = name;
    this.path = path;
    this.sameSite = sameSite;
    this.maxAgeDays = maxAgeDays;
  }

  public boolean isSecure() {
    return secure;
  }

  public String getName() {
    return name;
  }

  public String getPath() {
    return path;
  }

  public String getSameSite() {
    return sameSite;
  }

  public long getMaxAgeDays() {
    return maxAgeDays;
  }
}
