package io.ngss.atlas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link InternalSecretFilter} (Docker-free). Proves: blank secret constructs and
 * never grants (EC-11 / SEC-3 → the chain then 403s); a matching secret grants ROLE_INTERNAL; a
 * wrong secret does not; non-internal paths pass through untouched; and the comparison uses
 * constant-time {@link java.security.MessageDigest#isEqual} (SEC-1), not String.equals.
 */
class InternalSecretFilterUnitTest {

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static HttpServletRequest internalRequest(String secretHeader) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/internal/tasks/drain-outbox");
    when(req.getHeader("X-Internal-Secret")).thenReturn(secretHeader);
    return req;
  }

  private static boolean hasRoleInternal(Authentication auth) {
    return auth != null
        && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_INTERNAL".equals(a.getAuthority()));
  }

  @Test
  void blankSecretConstructsWithoutThrowingAndNeverGrants() throws Exception {
    InternalSecretFilter filter = new InternalSecretFilter("");
    FilterChain chain = mock(FilterChain.class);

    assertThatCode(
            () ->
                filter.doFilterInternal(
                    internalRequest("anything"), mock(HttpServletResponse.class), chain))
        .doesNotThrowAnyException();

    verify(chain).doFilter(any(), any());
    // A non-anonymous-but-unauthorized token is set → the security chain returns 403, never 200.
    assertThat(hasRoleInternal(SecurityContextHolder.getContext().getAuthentication())).isFalse();
  }

  @Test
  void matchingSecretGrantsRoleInternal() throws Exception {
    InternalSecretFilter filter = new InternalSecretFilter("the-real-secret-value");
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(
        internalRequest("the-real-secret-value"), mock(HttpServletResponse.class), chain);

    verify(chain).doFilter(any(), any());
    assertThat(hasRoleInternal(SecurityContextHolder.getContext().getAuthentication())).isTrue();
  }

  @Test
  void wrongSecretDoesNotGrant() throws Exception {
    InternalSecretFilter filter = new InternalSecretFilter("the-real-secret-value");
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(
        internalRequest("WRONG"), mock(HttpServletResponse.class), chain);

    assertThat(hasRoleInternal(SecurityContextHolder.getContext().getAuthentication())).isFalse();
  }

  @Test
  void nonInternalPathPassesThroughWithoutSettingAuthentication() throws Exception {
    InternalSecretFilter filter = new InternalSecretFilter("the-real-secret-value");
    FilterChain chain = mock(FilterChain.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/api/tickets");

    filter.doFilterInternal(req, mock(HttpServletResponse.class), chain);

    verify(chain).doFilter(any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void usesConstantTimeMessageDigestNotStringEquals() throws Exception {
    // SEC-1 static guard: the secret comparison must be constant-time.
    String source =
        Files.readString(
            Path.of("src/main/java/io/ngss/atlas/security/InternalSecretFilter.java"));
    assertThat(source).contains("MessageDigest.isEqual");
    assertThat(source).doesNotContain("configuredSecret.equals");
  }
}
