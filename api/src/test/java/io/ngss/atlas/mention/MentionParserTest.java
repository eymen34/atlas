package io.ngss.atlas.mention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MentionParser} (T-022 extraction rules; T-043 candidate cap).
 * Resolution is mocked — these assert the pure extraction rules (length cap, HTML
 * stripping, the @handle regex, trailing-punctuation stripping, adversarial input) and
 * the T-043 advisory cap on the number of distinct resolved candidates.
 *
 * <p>NOTE: the parser is constructed explicitly (NOT {@code @InjectMocks}) because the
 * cap is an {@code int} constructor parameter — {@code @InjectMocks} would inject Java's
 * default {@code 0} for it, silently capping every test at zero. Constructor injection
 * passes the value directly, no reflection needed.
 */
@ExtendWith(MockitoExtension.class)
class MentionParserTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID ALICE = UUID.randomUUID();

  /** Production default for {@code MENTION_MAX_CANDIDATES} (the {@code :50} in @Value). */
  private static final int DEFAULT_CAP = 50;

  @Mock UserRepository userRepository;
  @Captor ArgumentCaptor<Collection<String>> handlesCaptor;

  private MentionParser parser;

  @BeforeEach
  void setUp() {
    parser = new MentionParser(userRepository, DEFAULT_CAP);
  }

  private void resolvesToAlice() {
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenReturn(List.of(ALICE));
  }

  /** Echoes one distinct id per handle sent, so the result size mirrors the post-cap input. */
  private void resolvesEveryHandle() {
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenAnswer(
            inv -> {
              Collection<String> handles = inv.getArgument(1);
              return handles.stream().map(h -> UUID.randomUUID()).collect(Collectors.toList());
            });
  }

  private static String distinctHandles(int n) {
    return IntStream.rangeClosed(1, n)
        .mapToObj(i -> "@user" + String.format("%02d", i))
        .collect(Collectors.joining(" "));
  }

  // ───────────────────────── T-022 extraction rules ─────────────────────────

  @Test
  void bodyOverCap_returnsEmpty_withoutQuerying() {
    String huge = "@alice " + "x".repeat(20_000);
    assertThat(parser.parse(huge, PROJECT)).isEmpty();
    verify(userRepository, never()).findMemberIdsByProjectIdAndHandles(any(), any());
  }

  @Test
  void nullBody_returnsEmpty_withoutQuerying() {
    assertThat(parser.parse(null, PROJECT)).isEmpty();
    verify(userRepository, never()).findMemberIdsByProjectIdAndHandles(any(), any());
  }

  @Test
  void stripsHtmlTags_andResolvesVisibleHandle() {
    resolvesToAlice();
    Set<UUID> result = parser.parse("<p>hey @alice please look</p>", PROJECT);
    assertThat(result).containsExactly(ALICE);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue()).containsExactly("alice");
  }

  @Test
  void plainHandle_resolves() {
    resolvesToAlice();
    assertThat(parser.parse("@alice", PROJECT)).containsExactly(ALICE);
  }

  @Test
  void trailingPunctuation_isStripped() {
    resolvesToAlice();
    parser.parse("ping @alice.", PROJECT);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    // The trailing dot is dropped; an internal dot would be kept.
    assertThat(handlesCaptor.getValue()).containsExactly("alice");
  }

  @Test
  void bareEmail_isNotAMention_andDoesNotQuery() {
    assertThat(parser.parse("contact foo@bar.com please", PROJECT)).isEmpty();
    verify(userRepository, never()).findMemberIdsByProjectIdAndHandles(any(), any());
  }

  @Test
  void tipTapMentionMarkup_resolvesFromVisibleText_notAttributes() {
    resolvesToAlice();
    // EC-9: the data-id attribute is ignored; the visible "@alice" is what resolves.
    parser.parse("<p>cc <span data-id=\"99\" data-label=\"alice\">@alice</span></p>", PROJECT);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue()).containsExactly("alice");
    assertThat(handlesCaptor.getValue()).doesNotContain("99");
  }

  @Test
  void overlongToken_isBoundedToAtMost64Chars() {
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenReturn(List.of());
    parser.parse("@" + "a".repeat(200), PROJECT);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue())
        .allSatisfy(h -> assertThat(h.length()).isLessThanOrEqualTo(64));
  }

  @Test
  void unbalancedBrackets_doNotCrash() {
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenReturn(List.of(ALICE));
    // Missing closing '>' — must not throw, and still extracts the visible handle.
    assertThat(parser.parse("<span unfinished @alice", PROJECT)).containsExactly(ALICE);
  }

  // ───────────────────────── T-043 candidate cap ─────────────────────────

  @Test
  void capExceeded_resolvesAtMostDefaultCap() {
    resolvesEveryHandle();
    Set<UUID> result = assertDoesNotThrow(() -> parser.parse(distinctHandles(60), PROJECT));
    // AC-2: 60 distinct handles, cap 50 → exactly 50 resolved, no exception.
    assertThat(result).hasSize(DEFAULT_CAP);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    // D2 first-appearance order: the first 50 survive; @user51..@user60 are dropped.
    List<String> expectedFirst50 =
        IntStream.rangeClosed(1, 50)
            .mapToObj(i -> "user" + String.format("%02d", i))
            .collect(Collectors.toList());
    assertThat(handlesCaptor.getValue()).containsExactlyElementsOf(expectedFirst50);
    assertThat(handlesCaptor.getValue()).doesNotContain("user51", "user60");
  }

  @Test
  void exactlyAtCap_resolvesAll() {
    resolvesEveryHandle();
    Set<UUID> result = parser.parse(distinctHandles(50), PROJECT);
    // AC-3.2 boundary: at exactly the cap the `> effectiveCap` guard is skipped (NOT `>=`),
    // so all 50 resolve — no off-by-one truncation.
    assertThat(result).hasSize(50);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue()).hasSize(50);
  }

  @Test
  void underCap_resolvesAll() {
    resolvesEveryHandle();
    Set<UUID> result = parser.parse(distinctHandles(10), PROJECT);
    // AC-3.1: normal path unaffected by the cap.
    assertThat(result).hasSize(10);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue()).hasSize(10);
  }

  @Test
  void customCapValue_capsAtConfiguredValue() {
    resolvesEveryHandle();
    // AC-4: a different configured value (5) is honoured — proves the @Value is wired
    // through the constructor and configurable (no Spring context / ReflectionTestUtils).
    MentionParser capped5 = new MentionParser(userRepository, 5);
    Set<UUID> result = capped5.parse(distinctHandles(10), PROJECT);
    assertThat(result).hasSize(5);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue())
        .containsExactly("user01", "user02", "user03", "user04", "user05");
  }

  @Test
  void capZero_resolvesNone() {
    // EC-2: cap 0 → nobody resolvable, no exception, and the query is skipped entirely.
    MentionParser capped0 = new MentionParser(userRepository, 0);
    Set<UUID> result = assertDoesNotThrow(() -> capped0.parse("@dave @eve", PROJECT));
    assertThat(result).isEmpty();
    verifyNoInteractions(userRepository);
  }

  @Test
  void negativeCap_treatedAsZero() {
    // EC-1: a negative env value is clamped to 0 via Math.max — NOT an IllegalArgumentException
    // from subList(0, -1). Resolves nobody; the query is skipped.
    MentionParser cappedNeg = new MentionParser(userRepository, -1);
    Set<UUID> result = assertDoesNotThrow(() -> cappedNeg.parse("@carol", PROJECT));
    assertThat(result).isEmpty();
    verifyNoInteractions(userRepository);
  }

  @Test
  void duplicateHandles_dedupedBeforeCap() {
    resolvesEveryHandle();
    // EC-4 (D2): dedup happens BEFORE the cap. "@alice @alice @bob @carol" → distinct
    // [alice, bob, carol] → cap 2 → [alice, bob]; @alice is not counted twice, @carol drops.
    MentionParser capped2 = new MentionParser(userRepository, 2);
    capped2.parse("@alice @alice @bob @carol", PROJECT);
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue()).containsExactly("alice", "bob");
  }

  @Test
  void nonMemberHandle_notResolved() {
    // REG-4: the constructor refactor did not alter member-filter semantics — a handle the
    // repository does not resolve yields no mention.
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenReturn(List.of());
    Set<UUID> result = parser.parse("@unknown-user", PROJECT);
    assertThat(result).isEmpty();
    verify(userRepository).findMemberIdsByProjectIdAndHandles(eq(PROJECT), handlesCaptor.capture());
    assertThat(handlesCaptor.getValue()).containsExactly("unknown-user");
  }
}
