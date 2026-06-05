package io.ngss.atlas.project;

import java.util.UUID;

/**
 * Thrown when the target user of a role-change or removal is not a member of the
 * project. Mapped to HTTP 404 by GlobalExceptionHandler. Distinct from
 * {@link ProjectNotFoundException}: the caller is an admin who can see the
 * project, so revealing that the target membership is absent leaks nothing.
 */
public class MemberNotFoundException extends RuntimeException {

  public MemberNotFoundException(UUID projectId, UUID userId) {
    super("Membership not found.");
  }
}
