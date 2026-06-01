package io.ngss.atlas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** EC-8 / AC-1 unit coverage for JwtIssuer (no Docker, no Spring). */
class JwtIssuerTest {

  private static final String SECRET = "test-secret-min-32-chars-for-hs256-ok";

  @Test
  void constructionWithBlankSecretDoesNotThrow() {
    // AppCDS stage-3 boots the context with no JWT_SECRET; the bean MUST build.
    new JwtIssuer("", 900, Clock.systemUTC());
    new JwtIssuer(null, 900, Clock.systemUTC());
  }

  @Test
  void issueProducesVerifiableHs256TokenWith900sLifetime() throws Exception {
    Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
    JwtIssuer issuer = new JwtIssuer(SECRET, 900, Clock.fixed(fixed, ZoneOffset.UTC));
    UUID userId = UUID.randomUUID();

    SignedJWT jwt = SignedJWT.parse(issuer.issue(userId));

    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId.toString());

    Instant iat = jwt.getJWTClaimsSet().getIssueTime().toInstant();
    Instant exp = jwt.getJWTClaimsSet().getExpirationTime().toInstant();
    assertThat(iat).isEqualTo(fixed);
    assertThat(exp).isEqualTo(fixed.plusSeconds(900));
    assertThat(exp.getEpochSecond() - iat.getEpochSecond()).isEqualTo(900L);
  }

  @Test
  void issueWithBlankSecretThrowsAtCallTimeNotConstruction() {
    JwtIssuer issuer = new JwtIssuer("", 900, Clock.systemUTC());
    assertThatThrownBy(() -> issuer.issue(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }
}
