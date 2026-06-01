package io.ngss.atlas.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived HS256 access tokens (sub = userId). The secret is read
 * lazily: a blank {@code JWT_SECRET} must NOT fail construction (AppCDS stage-3
 * boots the context with no secret), only {@link #issue} throws.
 */
@Component
public class JwtIssuer {

  private final String secret;
  private final long accessTtlSeconds;
  private final Clock clock;

  @Autowired
  public JwtIssuer(
      @Value("${JWT_SECRET:}") String secret,
      @Value("${JWT_ACCESS_TTL_SECONDS:900}") long accessTtlSeconds) {
    this(secret, accessTtlSeconds, Clock.systemUTC());
  }

  // Package-private: tests inject a fixed Clock and explicit secret.
  JwtIssuer(String secret, long accessTtlSeconds, Clock clock) {
    this.secret = secret;
    this.accessTtlSeconds = accessTtlSeconds;
    this.clock = clock;
  }

  public String issue(UUID userId) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT_SECRET is not configured");
    }
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(accessTtlSeconds)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    try {
      jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign JWT", e);
    }
    return jwt.serialize();
  }

  public long accessTtlSeconds() {
    return accessTtlSeconds;
  }
}
