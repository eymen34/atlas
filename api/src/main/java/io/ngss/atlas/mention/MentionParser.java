package io.ngss.atlas.mention;

import io.ngss.atlas.domain.UserRepository;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Server-side @mention authority (T-022, D4). The client's mention metadata is
 * NEVER trusted — this parser re-derives the mentioned users from the raw HTML
 * body and resolves them against the project's membership.
 *
 * <p>Algorithm: pre-check length, strip HTML tags to spaces (so a TipTap
 * {@code <span data-id=... data-label=...>@alice</span>} resolves from the VISIBLE
 * "@alice" text, not the attributes), lowercase, find {@code @handle} tokens,
 * strip trailing punctuation, then resolve the distinct handles to the ids of the
 * project's members. Non-members and bare emails ({@code foo@bar.com}) resolve to
 * nothing.
 */
@Component
public class MentionParser {

  /** Bodies longer than the comment cap are rejected wholesale (no scan). */
  private static final int MAX_BODY = 16384;

  /** Reluctant, bounded tag matcher — bounded length avoids catastrophic backtracking. */
  private static final Pattern TAG = Pattern.compile("<[^>]{0,1024}?>");

  /** An @handle preceded by start-of-text or whitespace; handle charset bounded to 64. */
  private static final Pattern HANDLE = Pattern.compile("(?:^|\\s)@([a-z0-9._-]{1,64})");

  /** Trailing sentence punctuation to drop from a captured handle (e.g. "alice." → "alice"). */
  private static final Pattern TRAILING_PUNCT = Pattern.compile("[.,:;!?)]+$");

  private final UserRepository userRepository;

  public MentionParser(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * @return the distinct ids of project members mentioned in {@code body} (empty if
   *     the body is null, over the cap, or mentions nobody resolvable).
   */
  public Set<UUID> parse(String body, UUID projectId) {
    if (body == null || body.length() > MAX_BODY) {
      return Set.of();
    }
    String text = TAG.matcher(body).replaceAll(" ").toLowerCase(Locale.ROOT);

    Set<String> candidates = new LinkedHashSet<>();
    Matcher matcher = HANDLE.matcher(text);
    while (matcher.find()) {
      String handle = TRAILING_PUNCT.matcher(matcher.group(1)).replaceAll("");
      if (!handle.isEmpty() && handle.length() <= 64) {
        candidates.add(handle);
      }
    }
    if (candidates.isEmpty()) {
      return Set.of();
    }
    return new LinkedHashSet<>(
        userRepository.findMemberIdsByProjectIdAndHandles(projectId, candidates));
  }
}
