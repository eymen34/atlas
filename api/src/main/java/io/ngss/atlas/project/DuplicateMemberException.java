package io.ngss.atlas.project;

import java.util.UUID;

/**
 * Thrown when adding a user who is already a member of the project. Mapped to
 * HTTP 409 by GlobalExceptionHandler. Raised on the pre-check AND on the
 * UNIQUE(project_id, user_id) race (DataIntegrityViolationException caught and
 * rethrown), so a concurrent add surfaces as 409, never 500.
 */
public class DuplicateMemberException extends RuntimeException {

  public DuplicateMemberException(UUID projectId, UUID userId) {
    super("User is already a member of this project.");
  }
}
