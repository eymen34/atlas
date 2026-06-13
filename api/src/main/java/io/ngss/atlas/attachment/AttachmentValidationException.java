package io.ngss.atlas.attachment;

/**
 * Attachment request/state validation failure → 400 (T-025): oversize or
 * disallowed content-type at init, or a finalize HEAD mismatch (size/content-type)
 * or missing object. The message is safe to surface to the client.
 */
public class AttachmentValidationException extends RuntimeException {
  public AttachmentValidationException(String message) {
    super(message);
  }
}
