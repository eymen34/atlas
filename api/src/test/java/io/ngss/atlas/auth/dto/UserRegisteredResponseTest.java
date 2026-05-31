package io.ngss.atlas.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * REG-5 / QG-8: UserRegisteredResponse must serialize to exactly
 * {id, email, displayName, createdAt} — permanently token-free and hash-free.
 */
class UserRegisteredResponseTest {

  // findAndAddModules picks up the java.time datatype support so Instant
  // serializes (matches the app's configured mapper).
  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void serializesToExactlyFourTokenFreeFields() {
    UserRegisteredResponse response =
        new UserRegisteredResponse(UUID.randomUUID(), "test@test.com", "Test User", Instant.now());

    String json = mapper.writeValueAsString(response);
    Map<String, Object> fields = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

    assertThat(fields.keySet())
        .containsExactlyInAnyOrder("id", "email", "displayName", "createdAt");
    assertThat(json).doesNotContain("password");
    assertThat(json).doesNotContain("accessToken");
    assertThat(json).doesNotContain("refreshToken");
    assertThat(json).doesNotContain("bcryptHash");
  }
}
