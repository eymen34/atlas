package io.ngss.atlas.mention;

import java.util.Locale;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Derives a globally-unique @mention handle from an email local-part (T-022, D3).
 *
 * <p>The base is the lowercased local-part with non-handle characters removed,
 * truncated to 60 chars BEFORE any collision suffix (so a "-N" suffix can never
 * push it past the 64-char column limit), or {@code "user"} if empty. If the base
 * is taken, a numeric suffix ({@code base-2}, {@code base-3}, …) is tried up to
 * {@link #MAX_SUFFIX_ATTEMPTS} times; exhausting them throws
 * {@link HandleGenerationException}. The {@code isTaken} predicate is the pre-check
 * (DB existence); the unique index is the ultimate backstop for a concurrent race.
 */
@Component
public class MentionHandleGenerator {

  private static final int MAX_BASE_LENGTH = 60;
  private static final int MAX_SUFFIX_ATTEMPTS = 5;

  public String derive(String email, Predicate<String> isTaken) {
    String localPart = email == null ? "" : email.split("@", 2)[0];
    String slug = localPart.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
    String base =
        slug.isEmpty() ? "user" : slug.substring(0, Math.min(MAX_BASE_LENGTH, slug.length()));

    if (!isTaken.test(base)) {
      return base;
    }
    for (int suffix = 2; suffix <= MAX_SUFFIX_ATTEMPTS + 1; suffix++) {
      String candidate = base + "-" + suffix;
      if (!isTaken.test(candidate)) {
        return candidate;
      }
    }
    throw new HandleGenerationException(
        "could not derive a unique mention handle for base '" + base + "'");
  }
}
