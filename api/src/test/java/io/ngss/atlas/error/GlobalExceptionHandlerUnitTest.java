package io.ngss.atlas.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.domain.EmailAlreadyRegisteredException;
import io.ngss.atlas.error.GlobalExceptionHandler.ErrorBody;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Pure unit test (no Docker, no Spring context) for the error mappings. */
class GlobalExceptionHandlerUnitTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static MockHttpServletRequest postRegister() {
    return new MockHttpServletRequest("POST", "/api/auth/register");
  }

  @Test
  void emailAlreadyRegistered_maps409() {
    ResponseEntity<ErrorBody> response =
        handler.handleEmailAlreadyRegistered(
            new EmailAlreadyRegisteredException("x@y.com"), postRegister());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    ErrorBody body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(409);
    assertThat(body.error()).isEqualTo("Conflict");
    assertThat(body.message()).isEqualTo("email already registered");
    assertThat(body.path()).isEqualTo("/api/auth/register");
  }

  @Test
  void uniqueConstraintViolation_maps409() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"users_email_lower_key\""));

    ResponseEntity<ErrorBody> response = handler.handleDataIntegrityViolation(ex, postRegister());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().status()).isEqualTo(409);
    assertThat(response.getBody().message()).isEqualTo("email already registered");
  }

  @Test
  void unrelatedDataIntegrityViolation_maps500() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException("null value in column \"foo\" violates not-null constraint"));

    ResponseEntity<ErrorBody> response = handler.handleDataIntegrityViolation(ex, postRegister());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
