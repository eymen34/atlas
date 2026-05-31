package io.ngss.atlas.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration request (T-011 extends the T-009 scaffold with displayName).
 *
 * <p>Password safety (AC4 / SEC-1): the password is WRITE_ONLY — accepted on
 * input but never serialized back in any response — and toString() masks it.
 * Do NOT use {@code @JsonIgnore} on password; that would also block
 * deserialization (the value would never be read on input).
 */
public record RegisterRequest(
    @Email @NotBlank String email,
    @NotBlank @Size(min = 10, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,
    @NotBlank @Size(min = 1, max = 80) String displayName) {

  @Override
  public String toString() {
    return "RegisterRequest[email="
        + email
        + ", password=[PROTECTED], displayName="
        + displayName
        + "]";
  }
}
