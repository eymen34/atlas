package io.ngss.atlas.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T-009 stateless JWT verification filter. Reads {@code Authorization: Bearer
 * &lt;token&gt;}, verifies an HS256 signature against {@code JWT_SECRET}, and
 * populates the {@link SecurityContextHolder} with the subject claim on
 * success. Never touches the database.
 *
 * <p>AppCDS image-build safety: when {@code JWT_SECRET} is blank (the
 * default, used during the no-DB AppCDS warm-up in Dockerfile stage 3),
 * the filter short-circuits to {@code chain.doFilter} without instantiating
 * the MAC verifier. Production deployments MUST set a non-blank secret —
 * SecurityConfig's permit/deny rules still apply, so blank-secret traffic
 * to authenticated routes returns 401 via the entry point.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";

  private final String jwtSecret;

  public JwtAuthenticationFilter(@Value("${JWT_SECRET:}") String jwtSecret) {
    this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (jwtSecret.isBlank()) {
      chain.doFilter(request, response);
      return;
    }
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    if (token.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      // alg:none parses as PlainJWT — SignedJWT.parse rejects it. Belt and
      // braces: ensure the header algorithm is HS256 before verification.
      String alg = jwt.getHeader().getAlgorithm().getName();
      if (!"HS256".equals(alg)) {
        log.debug("rejecting JWT with unsupported algorithm: {}", alg);
        chain.doFilter(request, response);
        return;
      }
      MACVerifier verifier = new MACVerifier(jwtSecret.getBytes(StandardCharsets.UTF_8));
      if (!jwt.verify(verifier)) {
        log.debug("JWT signature verification failed");
        chain.doFilter(request, response);
        return;
      }
      Date exp = jwt.getJWTClaimsSet().getExpirationTime();
      if (exp != null && exp.before(new Date())) {
        log.debug("JWT expired at {}", exp);
        chain.doFilter(request, response);
        return;
      }
      String subject = jwt.getJWTClaimsSet().getSubject();
      UsernamePasswordAuthenticationToken auth =
          new UsernamePasswordAuthenticationToken(subject, null, Collections.emptyList());
      SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (ParseException | JOSEException ex) {
      log.debug("JWT parse/verify failed: {}", ex.getMessage());
    } catch (RuntimeException ex) {
      log.debug("unexpected error during JWT verification: {}", ex.getMessage());
    }
    chain.doFilter(request, response);
  }
}
