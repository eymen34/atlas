package io.ngss.atlas.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Static helper that reads the authenticated user id from the SecurityContext.
 *
 * <p>{@link JwtAuthenticationFilter} sets the principal to the raw JWT subject —
 * the user id as a {@code String}. This helper parses it back to a {@link UUID}.
 *
 * <p>Deliberately NOT a Spring bean and touches no external resource, so it has
 * zero impact on the Dockerfile stage-3 no-DB AppCDS boot.
 *
 * <p>Throws {@link IllegalStateException} when no authentication is present and
 * {@link IllegalArgumentException} when the principal is not a valid UUID. For
 * {@code /api/**} routes the security chain guarantees an authenticated
 * principal before any controller runs, so these are defensive paths; callers
 * that need a specific HTTP status (e.g. AuthController's 401) wrap the call.
 */
public final class CurrentUser {

  private CurrentUser() {}

  /**
   * @return the authenticated caller's user id
   * @throws IllegalStateException if there is no authentication in the context
   * @throws IllegalArgumentException if the principal is not a valid UUID string
   */
  public static UUID id() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getPrincipal() == null) {
      throw new IllegalStateException("No authenticated principal in the security context");
    }
    return UUID.fromString(auth.getPrincipal().toString());
  }
}
