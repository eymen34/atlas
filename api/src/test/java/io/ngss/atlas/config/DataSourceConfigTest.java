package io.ngss.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DataSourceConfigTest {

  @Test
  void appendsPgBouncerParamsToUrlWithoutQueryString() {
    String result =
        DataSourceConfig.appendPgBouncerParams("jdbc:postgresql://localhost:5432/atlas");
    assertThat(result).contains("prepareThreshold=0");
    assertThat(result).contains("preparedStatementCacheQueries=0");
    assertThat(result).contains("tcpKeepAlive=true");
    assertThat(result).contains("socketTimeout=10");
    assertThat(result).startsWith("jdbc:postgresql://localhost:5432/atlas?");
  }

  @Test
  void appendsPgBouncerParamsToUrlWithExistingQueryString() {
    String result =
        DataSourceConfig.appendPgBouncerParams(
            "jdbc:postgresql://localhost:5432/atlas?currentSchema=public");
    assertThat(result).contains("currentSchema=public");
    assertThat(result).contains("&prepareThreshold=0");
    assertThat(result).contains("&preparedStatementCacheQueries=0");
    assertThat(result).contains("&tcpKeepAlive=true");
    assertThat(result).contains("&socketTimeout=10");
  }

  @Test
  void doesNotDuplicateParamsAlreadyPresent() {
    String result =
        DataSourceConfig.appendPgBouncerParams(
            "jdbc:postgresql://localhost:5432/atlas?prepareThreshold=0");
    long occurrences = result.split("prepareThreshold=", -1).length - 1;
    assertThat(occurrences).isEqualTo(1L);
    assertThat(result).contains("preparedStatementCacheQueries=0");
  }
}
