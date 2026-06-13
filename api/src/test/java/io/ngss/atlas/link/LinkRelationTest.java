package io.ngss.atlas.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.ngss.atlas.domain.LinkRelation;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link LinkRelation} inverse mapping + user-facing matrix (T-026). */
class LinkRelationTest {

  @Test
  void inverse_mapsEachRelationToItsReciprocal() {
    assertThat(LinkRelation.inverse(LinkRelation.BLOCKS)).isEqualTo(LinkRelation.IS_BLOCKED_BY);
    assertThat(LinkRelation.inverse(LinkRelation.IS_BLOCKED_BY)).isEqualTo(LinkRelation.BLOCKS);
    assertThat(LinkRelation.inverse(LinkRelation.DUPLICATES))
        .isEqualTo(LinkRelation.IS_DUPLICATED_BY);
    assertThat(LinkRelation.inverse(LinkRelation.IS_DUPLICATED_BY))
        .isEqualTo(LinkRelation.DUPLICATES);
    assertThat(LinkRelation.inverse(LinkRelation.RELATES_TO)).isEqualTo(LinkRelation.RELATES_TO);
  }

  @Test
  void inverse_isInvolutive() {
    for (LinkRelation r : LinkRelation.values()) {
      assertThat(LinkRelation.inverse(LinkRelation.inverse(r))).isEqualTo(r);
    }
  }

  @Test
  void isUserFacing_trueOnlyForTheThreeCreatableTypes() {
    assertThat(LinkRelation.isUserFacing(LinkRelation.BLOCKS)).isTrue();
    assertThat(LinkRelation.isUserFacing(LinkRelation.DUPLICATES)).isTrue();
    assertThat(LinkRelation.isUserFacing(LinkRelation.RELATES_TO)).isTrue();
    assertThat(LinkRelation.isUserFacing(LinkRelation.IS_BLOCKED_BY)).isFalse();
    assertThat(LinkRelation.isUserFacing(LinkRelation.IS_DUPLICATED_BY)).isFalse();
  }
}
