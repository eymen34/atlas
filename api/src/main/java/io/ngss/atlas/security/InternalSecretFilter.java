package io.ngss.atlas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T-029 shared-secret gate for {@code /internal/**}. Mirrors {@link JwtAuthenticationFilter}:
 * the secret is read via {@code @Value("${OUTBOX_DRAIN_SHARED_SECRET:}")} with an empty default
 * and the comparison short-circuits when blank, so the stage-3 no-DB AppCDS boot (no secret set)
 * never crashes.
 *
 * <p>For every {@code /internal/} request this filter sets a NON-anonymous authentication so a
 * denial routes to the 403 {@code AccessDeniedHandler} rather than the 401 entry point:
 *
 * <ul>
 *   <li>secret configured AND header matches (constant-time {@link MessageDigest#isEqual}) →
 *       {@code ROLE_INTERNAL} (the drain endpoint's {@code hasAuthority} rule passes);
 *   <li>otherwise — missing header, wrong secret, OR blank/unconfigured secret → an
 *       empty-authority token, which fails every {@code /internal/**} rule → 403.
 * </ul>
 *
 * <p>It touches no database and only acts on {@code /internal/} paths; everything else passes
 * straight through to {@link JwtAuthenticationFilter}.
 */
@Component
public class InternalSecretFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Internal-Secret";
  static final String ROLE_INTERNAL = "ROLE_INTERNAL";
  private static final String INTERNAL_PREFIX = "/internal/";

  private final String configuredSecret;

  public InternalSecretFilter(@Value("${OUTBOX_DRAIN_SHARED_SECRET:}") String configuredSecret) {
    this.configuredSecret = configuredSecret == null ? "" : configuredSecret;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith(INTERNAL_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }
    if (secretMatches(request.getHeader(HEADER))) {
      authenticate("internal", List.of(new SimpleGrantedAuthority(ROLE_INTERNAL)));
    } else {
      // Non-anonymous but unauthorized → 403 (AccessDeniedHandler), never the 401 entry point.
      authenticate("internal-denied", List.of());
    }
    chain.doFilter(request, response);
  }

  /** Constant-time secret comparison; a blank configured secret or absent header never matches. */
  private boolean secretMatches(String presented) {
    if (configuredSecret.isBlank() || presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8),
        configuredSecret.getBytes(StandardCharsets.UTF_8));
  }

  private static void authenticate(String principal, List<SimpleGrantedAuthority> authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
  }
}
