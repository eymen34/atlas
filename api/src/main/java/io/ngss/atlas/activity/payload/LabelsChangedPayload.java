package io.ngss.atlas.activity.payload;

import java.util.List;
import java.util.UUID;

/**
 * Activity payload for {@code LABELS_CHANGED} — the delta of a label replace.
 * {@code added} and {@code removed} are defensively copied into immutable lists by
 * the compact constructor (callers may pass mutable lists).
 */
public record LabelsChangedPayload(List<UUID> added, List<UUID> removed) {

  public LabelsChangedPayload {
    added = List.copyOf(added);
    removed = List.copyOf(removed);
  }
}
