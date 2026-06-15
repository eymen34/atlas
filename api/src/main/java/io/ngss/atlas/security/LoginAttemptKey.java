package io.ngss.atlas.security;

/**
 * The two brute-force throttle buckets (T-033). {@code name()} maps to the
 * {@code login_attempts.key_type} CHECK values {@code 'ACCOUNT'} / {@code 'IP'}.
 */
public enum LoginAttemptKey {
  ACCOUNT,
  IP
}
