package io.ngss.atlas.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit test (no Docker, no Spring) for the password-safety contract on
 * RegisterRequest. Uses the same Jackson 3 (tools.jackson) mapper family the
 * app serializes with, so WRITE_ONLY behaviour is validated as it runs in prod.
 */
class RegisterRequestTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void toString_masksPassword() {
    RegisterRequest request = new RegisterRequest("a@b.com", "supersecretpw", "Alice");
    String rendered = request.toString();
    assertThat(rendered).doesNotContain("supersecretpw");
    assertThat(rendered).contains("[PROTECTED]");
    assertThat(rendered).contains("a@b.com");
    assertThat(rendered).contains("Alice");
  }

  @Test
  void jackson_acceptsPasswordOnInput() {
    String json = "{\"email\":\"a@b.com\",\"password\":\"CannotSeeMe99\",\"displayName\":\"Alice\"}";
    RegisterRequest request = mapper.readValue(json, RegisterRequest.class);
    assertThat(request.password()).isEqualTo("CannotSeeMe99");
    assertThat(request.email()).isEqualTo("a@b.com");
    assertThat(request.displayName()).isEqualTo("Alice");
  }

  @Test
  void jackson_omitsPasswordOnOutput() {
    RegisterRequest request = new RegisterRequest("a@b.com", "CannotSeeMe99", "Alice");
    String json = mapper.writeValueAsString(request);
    assertThat(json).doesNotContain("password");
    assertThat(json).doesNotContain("CannotSeeMe99");
    assertThat(json).contains("a@b.com");
    assertThat(json).contains("Alice");
  }
}
