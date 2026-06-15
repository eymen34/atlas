package io.ngss.atlas.security;

import java.time.Instant;

/**
 * A row of {@code login_attempts} mapped from a native query (T-033). NOT a JPA entity —
 * the table is native-only (no {@code @Entity}, entity count stays 17). {@code lockedUntil}
 * is null while the bucket is not locked.
 */
public record LoginAttemptRecord(int attemptCount, Instant firstAttemptAt, Instant lockedUntil) {}
