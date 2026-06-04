package io.ngss.atlas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for the {@link CurrentUser} principal → UUID contract. */
class CurrentUserTest {

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsUuidFromStringPrincipal() {
    UUID id = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(id.toString(), null, Collections.emptyList()));

    assertThat(CurrentUser.id()).isEqualTo(id);
  }

  @Test
  void throwsIllegalStateWhenNoAuthentication() {
    SecurityContextHolder.clearContext();

    assertThatThrownBy(CurrentUser::id).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void throwsIllegalArgumentWhenPrincipalIsNotUuid() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("not-a-uuid", null, Collections.emptyList()));

    assertThatThrownBy(CurrentUser::id).isInstanceOf(IllegalArgumentException.class);
  }
}
