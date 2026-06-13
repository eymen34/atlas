package io.ngss.atlas.attachment;

import java.util.UUID;

/**
 * Raised for a genuinely-missing attachment, a soft-deleted one, a foreign one
 * (finalize by a non-uploader), or a thumbnail request with no thumbnail — all map
 * to a uniform 404 so no existence/ownership is leaked (IDOR-safe). (T-025)
 */
public class AttachmentNotFoundException extends RuntimeException {
  public AttachmentNotFoundException(UUID id) {
    super("attachment not found: " + id);
  }
}
