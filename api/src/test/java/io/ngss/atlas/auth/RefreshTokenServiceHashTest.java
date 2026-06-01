package io.ngss.atlas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SEC-2 unit coverage for the token hashing/generation helpers (no Docker). */
class RefreshTokenServiceHashTest {

  @Test
  void sha256HexMatchesKnownVectors() {
    assertThat(RefreshTokenService.sha256Hex(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(RefreshTokenService.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    // Output is always 64 lowercase hex chars.
    assertThat(RefreshTokenService.sha256Hex("anything")).hasSize(64).matches("^[0-9a-f]{64}$");
  }

  @Test
  void rawRefreshTokenIsBase64Url43CharsAndRandom() {
    String raw = RefreshTokenService.generateRawRefreshToken();
    assertThat(raw).hasSize(43).matches("^[A-Za-z0-9_-]+$");
    assertThat(RefreshTokenService.generateRawRefreshToken()).isNotEqualTo(raw);
  }
}
