package io.ngss.atlas.project;

import java.util.UUID;

/**
 * Thrown when demoting or removing a project's sole remaining ADMIN. Mapped to
 * HTTP 400 by GlobalExceptionHandler. The guard is race-safe: the check runs
 * after a pessimistic lock on the project's admin rows.
 */
public class LastAdminException extends RuntimeException {

  public LastAdminException(UUID projectId) {
    super("Project " + projectId + " would have no remaining ADMIN; demotion/removal blocked.");
  }
}
