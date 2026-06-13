package io.ngss.atlas.link;

/**
 * Raised when a link already exists between the pair in EITHER direction (D4) → 409
 * (T-026). One relation per pair; delete-then-re-add to change it. Detected by an
 * optimistic pre-check (NOT by catching the uq_link DataIntegrityViolation —
 * jpa_rollback_only_trap), so no insert is attempted.
 */
public class LinkConflictException extends RuntimeException {
  public LinkConflictException(String message) {
    super(message);
  }
}
