package io.ngss.atlas.mention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MentionParser} (T-022, D4). Resolution is mocked — these
 * assert the pure extraction rules: length cap, HTML stripping, the @handle regex,
 * trailing-punctuation stripping, and the bare-email / adversarial-input behaviour.
 */
@ExtendWith(MockitoExtension.class)
class MentionParserTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID ALICE = UUID.randomUUID();

  @Mock UserRepository userRepository;
  @InjectMocks MentionParser parser;
  @Captor ArgumentCaptor<Collection<String>> handlesCaptor;

  private void resolvesToAlice() {
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenReturn(List.of(ALICE));
  }

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
    parser.parse(
        "<p>cc <span data-id=\"99\" data-label=\"alice\">@alice</span></p>", PROJECT);
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
    assertThat(handlesCaptor.getValue()).allSatisfy(h -> assertThat(h.length()).isLessThanOrEqualTo(64));
  }

  @Test
  void unbalancedBrackets_doNotCrash() {
    when(userRepository.findMemberIdsByProjectIdAndHandles(eq(PROJECT), anyCollection()))
        .thenReturn(List.of(ALICE));
    // Missing closing '>' — must not throw, and still extracts the visible handle.
    assertThat(parser.parse("<span unfinished @alice", PROJECT)).containsExactly(ALICE);
  }
}
