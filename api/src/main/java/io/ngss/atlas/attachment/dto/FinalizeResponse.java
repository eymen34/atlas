package io.ngss.atlas.attachment.dto;

/**
 * Result of a finalize call (T-025). Finalize is a STATE-MACHINE step, not a
 * validation endpoint: a size/content-type mismatch is a normal outcome
 * ({@code status="FAILED"}), NOT an HTTP 4xx. Returning 200 + this body lets the
 * {@code @Transactional} method COMMIT the FAILED row — throwing would mark the tx
 * rollback-only and silently discard the status write (jpa_rollback_only_trap),
 * leaving the row stuck PENDING.
 *
 * @param status {@code "READY"} or {@code "FAILED"}
 * @param reason {@code null} when READY; otherwise {@code "size_mismatch"},
 *     {@code "content_type_mismatch"}, or {@code "object_missing"}
 */
public record FinalizeResponse(String status, String reason) {

  public static FinalizeResponse ready() {
    return new FinalizeResponse("READY", null);
  }

  public static FinalizeResponse failed(String reason) {
    return new FinalizeResponse("FAILED", reason);
  }
}
