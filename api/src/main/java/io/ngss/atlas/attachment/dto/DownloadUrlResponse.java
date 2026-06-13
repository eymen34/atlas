package io.ngss.atlas.attachment.dto;

/** A short-lived presigned GET URL for an attachment (or its thumbnail) (T-025). */
public record DownloadUrlResponse(String url) {}
