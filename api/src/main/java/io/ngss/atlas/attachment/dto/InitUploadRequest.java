package io.ngss.atlas.attachment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /api/tickets/{id}/attachments/init (T-025). {@code sizeBytes} is the
 * CLAIMED size — init validates the claim against ATTACHMENT_MAX_SIZE_BYTES, but the
 * real gate is the finalize HEAD (a presigned PUT cannot enforce a max size; D5).
 */
public record InitUploadRequest(
    @NotBlank String filename, @NotBlank String contentType, @Positive long sizeBytes) {}
