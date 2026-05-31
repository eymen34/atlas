package io.ngss.atlas.config;

import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStartupCheckInfoContributor implements InfoContributor {

  private final DatabaseStartupValidator validator;

  public DatabaseStartupCheckInfoContributor(DatabaseStartupValidator validator) {
    this.validator = validator;
  }

  @Override
  public void contribute(Info.Builder builder) {
    builder.withDetail(
        "databaseStartupCheck", Map.of("enabled", validator.isStartupCheckEnabled()));
  }
}
