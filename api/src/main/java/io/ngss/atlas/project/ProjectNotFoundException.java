package io.ngss.atlas.project;

/**
 * Thrown when a project cannot be resolved for the caller. Mapped to HTTP 404 by
 * GlobalExceptionHandler.
 *
 * <p>Deliberately raised for BOTH genuinely-missing projects AND projects that
 * exist but are not owned by the caller (T-014 creator-only access). Collapsing
 * "not yours" to 404 rather than 403 prevents existence leakage (AC4).
 */
public class ProjectNotFoundException extends RuntimeException {

  public ProjectNotFoundException() {
    super("Project not found");
  }
}
