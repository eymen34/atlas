package io.ngss.atlas.link;

/**
 * Link request validation failure → 400 (T-026): self-link, unknown/cross-project
 * target key, or an inverse (server-derived) relation type on create. The message is
 * safe to surface; the unknown/cross-project case uses a UNIFORM "Unknown ticket key"
 * so a caller cannot probe another project's existence.
 */
public class LinkValidationException extends RuntimeException {
  public LinkValidationException(String message) {
    super(message);
  }
}
