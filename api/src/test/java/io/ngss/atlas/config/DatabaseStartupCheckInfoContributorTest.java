package io.ngss.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

class DatabaseStartupCheckInfoContributorTest {

  @Test
  void contributesEnabledTrueDetail() {
    DatabaseStartupValidator validator = mock(DatabaseStartupValidator.class);
    when(validator.isStartupCheckEnabled()).thenReturn(true);
    DatabaseStartupCheckInfoContributor contributor =
        new DatabaseStartupCheckInfoContributor(validator);
    Info.Builder builder = new Info.Builder();

    contributor.contribute(builder);
    Info info = builder.build();

    Object detail = info.getDetails().get("databaseStartupCheck");
    assertThat(detail).isInstanceOf(java.util.Map.class);
    assertThat(((java.util.Map<?, ?>) detail).get("enabled")).isEqualTo(true);
  }

  @Test
  void contributesEnabledFalseDetail() {
    DatabaseStartupValidator validator = mock(DatabaseStartupValidator.class);
    when(validator.isStartupCheckEnabled()).thenReturn(false);
    DatabaseStartupCheckInfoContributor contributor =
        new DatabaseStartupCheckInfoContributor(validator);
    Info.Builder builder = new Info.Builder();

    contributor.contribute(builder);
    Info info = builder.build();

    Object detail = info.getDetails().get("databaseStartupCheck");
    assertThat(((java.util.Map<?, ?>) detail).get("enabled")).isEqualTo(false);
  }
}
