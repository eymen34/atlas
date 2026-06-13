package io.ngss.atlas.outbox;

/**
 * Summary of one drain pass (T-029). Invariant: {@code processed == succeeded + failed +
 * retried} — every claimed row resolves to exactly one of the three outcomes.
 */
public record DrainResult(int processed, int succeeded, int failed, int retried) {}
