package io.ngss.atlas.domain;

/**
 * A user's role within a project (T-015). Persisted as a string via
 * {@code @Enumerated(EnumType.STRING)} and emitted as a string enum in the
 * OpenAPI spec. ADMIN can manage members and mutate the project; MEMBER has
 * read/visibility access only.
 */
public enum ProjectRole {
  MEMBER,
  ADMIN
}
