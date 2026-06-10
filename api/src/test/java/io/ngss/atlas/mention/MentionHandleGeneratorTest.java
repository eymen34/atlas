package io.ngss.atlas.mention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MentionHandleGenerator} (T-022, D3). */
class MentionHandleGeneratorTest {

  private final MentionHandleGenerator generator = new MentionHandleGenerator();

  @Test
  void derivesLowercaseSlugFromLocalPart() {
    assertThat(generator.derive("Alice.Smith@Example.com", h -> false)).isEqualTo("alice.smith");
  }

  @Test
  void stripsDisallowedCharactersFromLocalPart() {
    assertThat(generator.derive("a+b!c@x.io", h -> false)).isEqualTo("abc");
  }

  @Test
  void blankSlugFallsBackToUser() {
    assertThat(generator.derive("+++@x.io", h -> false)).isEqualTo("user");
  }

  @Test
  void truncatesBaseToSixtyCharsBeforeAnySuffix() {
    String local = "a".repeat(80);
    assertThat(generator.derive(local + "@x.io", h -> false)).isEqualTo("a".repeat(60));
  }

  @Test
  void appendsNumericSuffixWhenBaseTaken() {
    Set<String> taken = Set.of("alice", "alice-2");
    assertThat(generator.derive("alice@x.io", taken::contains)).isEqualTo("alice-3");
  }

  @Test
  void throwsWhenSuffixBudgetExhausted() {
    Predicate<String> alwaysTaken = h -> true;
    assertThatThrownBy(() -> generator.derive("alice@x.io", alwaysTaken))
        .isInstanceOf(HandleGenerationException.class);
  }
}
