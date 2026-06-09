package io.ngss.atlas.label.dto;

import io.ngss.atlas.domain.Label;
import java.time.Instant;
import java.util.UUID;

/** Response body for label endpoints (also used for list elements). */
public record LabelResponse(
    UUID id, UUID projectId, String name, String color, Instant createdAt) {

  public static LabelResponse from(Label label) {
    return new LabelResponse(
        label.getId(),
        label.getProjectId(),
        label.getName(),
        label.getColor(),
        label.getCreatedAt());
  }
}
