package io.ngss.atlas.attachment.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Response of upload-init (T-025): the new attachment's id, the presigned PUT URL
 * (10-min TTL, Content-Type signed), and the {@code headers} the client MUST send on
 * that PUT (notably {@code Content-Type} — it is part of the signature, so a
 * different value fails the PUT).
 */
public record InitUploadResponse(UUID attachmentId, String uploadUrl, Map<String, String> headers) {}
