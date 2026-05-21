package io.ngss.atlas.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {

  static final String[] PGBOUNCER_PARAMS = {
    "prepareThreshold=0", "preparedStatementCacheQueries=0", "tcpKeepAlive=true", "socketTimeout=10"
  };

  static final String DB_POOL_MAX_ENV = "DB_POOL_MAX";
  static final String DB_POOL_MIN_ENV = "DB_POOL_MIN";

  @Bean
  DataSource dataSource(DatabaseProperties props) {
    int maxPool = readInt(DB_POOL_MAX_ENV, 10);
    int minIdle = readInt(DB_POOL_MIN_ENV, 2);
    if (minIdle > maxPool) {
      throw new IllegalStateException(
          "DB_POOL_MIN ("
              + minIdle
              + ") must be <= DB_POOL_MAX ("
              + maxPool
              + "). Adjust the environment variables.");
    }

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(appendPgBouncerParams(props.getUrl()));
    cfg.setUsername(props.getUsername());
    cfg.setPassword(props.getPassword());
    cfg.setMaximumPoolSize(maxPool);
    cfg.setMinimumIdle(minIdle);
    cfg.setConnectionTimeout(5000L);
    cfg.setIdleTimeout(30000L);
    cfg.setInitializationFailTimeout(-1L);
    cfg.setPoolName("atlas-pool");
    return new HikariDataSource(cfg);
  }

  static String appendPgBouncerParams(String baseUrl) {
    StringBuilder sb = new StringBuilder(baseUrl);
    boolean hasQuery = baseUrl.contains("?");
    for (String p : PGBOUNCER_PARAMS) {
      String key = p.substring(0, p.indexOf('=')) + "=";
      if (baseUrl.contains(key)) {
        continue;
      }
      sb.append(hasQuery ? '&' : '?').append(p);
      hasQuery = true;
    }
    return sb.toString();
  }

  private static int readInt(String envName, int defaultValue) {
    String raw = System.getenv(envName);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      throw new IllegalStateException(
          envName + " must be an integer; got " + raw + ".", ex);
    }
  }
}
