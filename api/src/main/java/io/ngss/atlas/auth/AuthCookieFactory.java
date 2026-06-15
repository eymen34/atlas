package io.ngss.atlas.auth;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code atlas_refresh} HttpOnly refresh-token cookie (T-048). {@link #COOKIE_NAME} is
 * the SINGLE source of truth for the cookie name — every {@code @CookieValue} read, every
 * {@code Set-Cookie} write, and the clear-cookie all use it (never a string literal, never
 * {@link CookieProperties#getName()}).
 *
 * <p>Attributes: HttpOnly (JS cannot read it — XSS exfiltration of the 30-day credential is the
 * threat this ticket closes) + Secure (config {@code app.auth.cookie.secure}) + SameSite=Lax (the
 * CSRF defence — see docs/security.md) + Path=/api/auth (scopes the cookie to the auth endpoints
 * that need it) + Max-Age = the refresh-token TTL. The clear cookie repeats the SAME attributes
 * with an empty value and Max-Age=0 (a Set-Cookie only overwrites/expires a prior cookie when its
 * name/path match).
 */
@Component
public class AuthCookieFactory {

  /** The refresh cookie name — the single constant shared by set + read + clear. */
  public static final String COOKIE_NAME = "atlas_refresh";

  private final CookieProperties props;

  public AuthCookieFactory(CookieProperties props) {
    this.props = props;
  }

  /** A fresh refresh cookie carrying {@code rawToken}, expiring after the configured TTL. */
  public ResponseCookie buildRefreshCookie(String rawToken) {
    return base(rawToken).maxAge(Duration.ofDays(props.getMaxAgeDays())).build();
  }

  /** A clearing cookie (empty value, Max-Age=0) that expires the refresh cookie in the browser. */
  public ResponseCookie buildClearCookie() {
    return base("").maxAge(Duration.ZERO).build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(props.isSecure())
        .sameSite(props.getSameSite())
        .path(props.getPath());
  }

  /** Appends the cookie as a {@code Set-Cookie} response header. */
  public void writeTo(HttpHeaders headers, ResponseCookie cookie) {
    headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
