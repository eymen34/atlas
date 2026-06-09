package io.ngss.atlas.error;

import io.ngss.atlas.auth.ForbiddenTokenAccessException;
import io.ngss.atlas.auth.InvalidCredentialsException;
import io.ngss.atlas.domain.EmailAlreadyRegisteredException;
import io.ngss.atlas.label.CrossProjectLabelException;
import io.ngss.atlas.label.DuplicateLabelNameException;
import io.ngss.atlas.label.LabelNotFoundException;
import io.ngss.atlas.label.LabelValidationException;
import io.ngss.atlas.project.DuplicateMemberException;
import io.ngss.atlas.project.DuplicateProjectKeyException;
import io.ngss.atlas.project.ForbiddenProjectAccessException;
import io.ngss.atlas.project.LastAdminException;
import io.ngss.atlas.project.MemberNotFoundException;
import io.ngss.atlas.project.ProjectNotFoundException;
import io.ngss.atlas.project.ProjectValidationException;
import io.ngss.atlas.project.UserNotFoundException;
import io.ngss.atlas.ticket.InvalidQueryParamException;
import io.ngss.atlas.ticket.TicketNotFoundException;
import io.ngss.atlas.ticket.TicketValidationException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

  @ExceptionHandler(ProjectNotFoundException.class)
  public ResponseEntity<ErrorBody> handleProjectNotFound(
      ProjectNotFoundException ex, HttpServletRequest request) {
    // Raised for genuinely-missing AND non-creator access — message is uniform
    // ("Project not found") so a 404 never reveals whether the project exists.
    log.info("project not found path={}", request.getRequestURI());
    return build(HttpStatus.NOT_FOUND, "Project not found", request);
  }

  @ExceptionHandler(DuplicateProjectKeyException.class)
  public ResponseEntity<ErrorBody> handleDuplicateProjectKey(
      DuplicateProjectKeyException ex, HttpServletRequest request) {
    log.info("project key conflict path={}", request.getRequestURI());
    return build(HttpStatus.CONFLICT, "Project key already in use", request);
  }

  @ExceptionHandler(ProjectValidationException.class)
  public ResponseEntity<ErrorBody> handleProjectValidation(
      ProjectValidationException ex, HttpServletRequest request) {
    log.info("project validation failure path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
  }

  @ExceptionHandler(ForbiddenProjectAccessException.class)
  public ResponseEntity<ErrorBody> handleForbiddenProjectAccess(
      ForbiddenProjectAccessException ex, HttpServletRequest request) {
    // Caller IS a member (existence already disclosed) but lacks ADMIN → 403.
    log.info("forbidden project access path={}", request.getRequestURI());
    return build(HttpStatus.FORBIDDEN, "Admin role required", request);
  }

  @ExceptionHandler(MemberNotFoundException.class)
  public ResponseEntity<ErrorBody> handleMemberNotFound(
      MemberNotFoundException ex, HttpServletRequest request) {
    log.info("project member not found path={}", request.getRequestURI());
    return build(HttpStatus.NOT_FOUND, "Membership not found", request);
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorBody> handleUserNotFound(
      UserNotFoundException ex, HttpServletRequest request) {
    log.info("user not found path={}", request.getRequestURI());
    return build(HttpStatus.NOT_FOUND, "User not found", request);
  }

  @ExceptionHandler(DuplicateMemberException.class)
  public ResponseEntity<ErrorBody> handleDuplicateMember(
      DuplicateMemberException ex, HttpServletRequest request) {
    log.info("duplicate project member path={}", request.getRequestURI());
    return build(HttpStatus.CONFLICT, "User is already a member of this project", request);
  }

  @ExceptionHandler(LastAdminException.class)
  public ResponseEntity<ErrorBody> handleLastAdmin(
      LastAdminException ex, HttpServletRequest request) {
    log.info("last-admin guard tripped path={}", request.getRequestURI());
    return build(
        HttpStatus.BAD_REQUEST,
        "Project would have no remaining ADMIN; demotion/removal blocked",
        request);
  }

  @ExceptionHandler(TicketNotFoundException.class)
  public ResponseEntity<ErrorBody> handleTicketNotFound(
      TicketNotFoundException ex, HttpServletRequest request) {
    // Raised for missing/soft-deleted/unresolvable tickets AND (post membership
    // check) tickets in a project the caller cannot see — uniform 404, no leak.
    log.info("ticket not found path={}", request.getRequestURI());
    return build(HttpStatus.NOT_FOUND, "Ticket not found", request);
  }

  @ExceptionHandler(TicketValidationException.class)
  public ResponseEntity<ErrorBody> handleTicketValidation(
      TicketValidationException ex, HttpServletRequest request) {
    log.info("ticket validation failure path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
  }

  @ExceptionHandler(LabelNotFoundException.class)
  public ResponseEntity<ErrorBody> handleLabelNotFound(
      LabelNotFoundException ex, HttpServletRequest request) {
    // Missing label OR (post membership check) a label in a project the caller
    // cannot see — uniform 404, no existence leak.
    log.info("label not found path={}", request.getRequestURI());
    return build(HttpStatus.NOT_FOUND, "Label not found", request);
  }

  @ExceptionHandler(DuplicateLabelNameException.class)
  public ResponseEntity<ErrorBody> handleDuplicateLabelName(
      DuplicateLabelNameException ex, HttpServletRequest request) {
    log.info("label name conflict path={}", request.getRequestURI());
    return build(HttpStatus.CONFLICT, ex.getMessage(), request);
  }

  @ExceptionHandler(LabelValidationException.class)
  public ResponseEntity<ErrorBody> handleLabelValidation(
      LabelValidationException ex, HttpServletRequest request) {
    log.info("label validation failure path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
  }

  @ExceptionHandler(CrossProjectLabelException.class)
  public ResponseEntity<ErrorBody> handleCrossProjectLabel(
      CrossProjectLabelException ex, HttpServletRequest request) {
    log.info("cross-project label rejected path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
  }

  @ExceptionHandler(InvalidQueryParamException.class)
  public ResponseEntity<ErrorBody> handleInvalidQueryParam(
      InvalidQueryParamException ex, HttpServletRequest request) {
    log.info("invalid query parameter path={}", request.getRequestURI());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorBody> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    // e.g. a non-UUID {id} path segment on PATCH/DELETE /api/projects/{id}.
    // Map to 400 (not the default 500) with the canonical error body. Never echo
    // the rejected value — only the parameter name.
    log.info("path-variable type mismatch path={} param={}", request.getRequestURI(), ex.getName());
    return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter: " + ex.getName(), request);
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
