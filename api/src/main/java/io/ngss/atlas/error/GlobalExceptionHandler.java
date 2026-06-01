package io.ngss.atlas.error;

import io.ngss.atlas.auth.ForbiddenTokenAccessException;
import io.ngss.atlas.auth.InvalidCredentialsException;
import io.ngss.atlas.domain.EmailAlreadyRegisteredException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Controller-advice error mapping for the REST API. Emits a stable JSON body
 * {@code {status, error, message, path}}.
 *
 * <p>Security-filter errors (401/403) are handled separately by
 * JsonAuthenticationEntryPoint / JsonAccessDeniedHandler and use a different
 * body shape ({@code {error, message, requestId}}) — those fire before the
 * dispatcher, so this advice never sees them.
 *
 * <p>Password-safety (AC4 / SEC-1): never log request bodies or rejected field
 * values. Validation messages are built from field name + constraint message
 * only, so a too-short password never echoes the attempted value.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Stable error envelope returned for every handled exception. */
  public record ErrorBody(int status, String error, String message, String path) {}

  @ExceptionHandler(EmailAlreadyRegisteredException.class)
  public ResponseEntity<ErrorBody> handleEmailAlreadyRegistered(
      EmailAlreadyRegisteredException ex, HttpServletRequest request) {
    log.info("registration conflict (email already registered) path={}", request.getRequestURI());
    return build(HttpStatus.CONFLICT, "email already registered", request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorBody> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    String rootMessage = mostSpecificMessage(ex);
    // users_email_lower_key is the V1 functional unique index on lower(email).
    //  Renaming this index in a future migration would silently downgrade this path to 500.
    if (rootMessage != null
        && (rootMessage.contains("users_email_lower_key")
            || rootMessage.toLowerCase(Locale.ROOT).contains("duplicate key"))) {
      log.info("registration conflict (unique constraint) path={}", request.getRequestURI());
      return build(HttpStatus.CONFLICT, "email already registered", request);
    }
    log.error(
        "data integrity violation path={} type={}",
        request.getRequestURI(),
        ex.getClass().getSimpleName());
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorBody> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest request) {
    // Uniform 401 for unknown-email AND wrong-password (anti-enumeration): the
    // body is byte-for-byte identical because the message is always "Invalid
    // credentials" and the path is the same.
    log.info("authentication failure path={}", request.getRequestURI());
    return build(HttpStatus.UNAUTHORIZED, "Invalid credentials", request);
  }

  @ExceptionHandler(ForbiddenTokenAccessException.class)
  public ResponseEntity<ErrorBody> handleForbiddenTokenAccess(
      ForbiddenTokenAccessException ex, HttpServletRequest request) {
    log.info("forbidden token access path={}", request.getRequestURI());
    return build(HttpStatus.FORBIDDEN, "Forbidden", request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorBody> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    // field + default message ONLY — never the rejected value (password leak risk).
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
            .collect(Collectors.joining("; "));
    if (message.isBlank()) {
      message = "Validation failed";
    }
    log.info("validation failure path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, message, request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorBody> handleUnreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    log.info("unreadable request body path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, "Malformed request body", request);
  }

  private ResponseEntity<ErrorBody> build(
      HttpStatus status, String message, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(new ErrorBody(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
  }

  private static String mostSpecificMessage(Throwable ex) {
    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage();
  }
}
