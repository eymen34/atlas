package io.ngss.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ApplicationYamlPropertiesTest {

  @Test
  @SuppressWarnings("unchecked")
  void hibernateAndJpaPropertiesAreSetCorrectly() throws Exception {
    Map<String, Object> root;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("application.yml")) {
      assertThat(in).as("application.yml must be on the classpath").isNotNull();
      root = new Yaml().load(in);
    }

    Map<String, Object> spring = (Map<String, Object>) root.get("spring");
    Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
    assertThat(jpa.get("open-in-view")).isEqualTo(false);

    Map<String, Object> jpaProps = (Map<String, Object>) jpa.get("properties");
    Map<String, Object> hibernate = (Map<String, Object>) jpaProps.get("hibernate");
    Map<String, Object> jdbc = (Map<String, Object>) hibernate.get("jdbc");
    Map<String, Object> lob = (Map<String, Object>) jdbc.get("lob");
    assertThat(lob.get("non_contextual_creation")).isEqualTo(true);

    Map<String, Object> query = (Map<String, Object>) hibernate.get("query");
    assertThat(query.get("plan_cache_max_size")).isEqualTo(512);

    Map<String, Object> boot = (Map<String, Object>) hibernate.get("boot");
    assertThat(boot.get("allow_jdbc_metadata_access")).isEqualTo(false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void hikariInitializationFailTimeoutIsDisabled() throws Exception {
    Map<String, Object> root;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("application.yml")) {
      root = new Yaml().load(in);
    }
    Map<String, Object> spring = (Map<String, Object>) root.get("spring");
    Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
    Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
    assertThat(hikari.get("initialization-fail-timeout")).isEqualTo(-1);
  }
}
