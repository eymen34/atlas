package io.ngss.atlas.project;

import java.util.UUID;

/**
 * Thrown when a caller IS a member of a project but lacks the ADMIN role
 * required for an admin-only action. Mapped to HTTP 403 by GlobalExceptionHandler.
 *
 * <p>Contrast with {@link ProjectNotFoundException} (404): a non-member never
 * reaches a 403 — non-membership collapses to 404 to avoid existence leakage.
 * Reaching 403 therefore confirms the caller can already see the project.
 */
public class ForbiddenProjectAccessException extends RuntimeException {

  public ForbiddenProjectAccessException(UUID projectId) {
    super("Admin role required for project " + projectId);
  }
}
