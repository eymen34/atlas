package io.ngss.atlas.link;

import java.util.UUID;

/** Raised when a link id does not exist (or was already deleted) → 404 (T-026). */
public class LinkNotFoundException extends RuntimeException {
  public LinkNotFoundException(UUID id) {
    super("link not found: " + id);
  }
}
