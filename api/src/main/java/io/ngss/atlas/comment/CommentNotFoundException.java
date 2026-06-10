package io.ngss.atlas.comment;

/**
 * Thrown when a comment cannot be resolved for the caller (genuinely missing OR
 * already soft-deleted, for edit/delete). Mapped to HTTP 404 by
 * GlobalExceptionHandler — a uniform 404 avoids existence leakage.
 */
public class CommentNotFoundException extends RuntimeException {

  public CommentNotFoundException(String message) {
    super(message);
  }
}
