package io.ngss.atlas.project;

import java.util.UUID;

/**
 * Thrown when a project cannot be resolved for the caller. Mapped to HTTP 404 by
 * GlobalExceptionHandler.
 *
 * <p>Deliberately raised for BOTH genuinely-missing projects AND projects the
 * caller cannot see (T-015: non-membership). Collapsing "not visible to you" to
 * 404 rather than 403 prevents existence leakage (AC2).
 */
public class ProjectNotFoundException extends RuntimeException {

  public ProjectNotFoundException() {
    super("Project not found");
  }

  public ProjectNotFoundException(UUID projectId) {
    super("Project not found");
  }
}
