package io.ngss.atlas.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStartupValidator implements SmartLifecycle {

  private final DataSource dataSource;
  private final String configuredUrl;
  private volatile boolean running = false;

  public DatabaseStartupValidator(DataSource dataSource, DatabaseProperties properties) {
    this.dataSource = dataSource;
    this.configuredUrl = properties.getUrl();
  }

  @Override
  public void start() {
    try (Connection c = dataSource.getConnection()) {
      if (!c.isValid(2)) {
        throw failFast(null);
      }
    } catch (SQLException ex) {
      throw failFast(ex);
    } catch (RuntimeException ex) {
      throw failFast(ex);
    }
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  private ApplicationContextException failFast(Throwable cause) {
    String msg =
        "Database connectivity check failed. Cannot reach database at "
            + sanitizeUrl(configuredUrl)
            + ". Ensure DATABASE_URL points to a reachable PostgreSQL 17 instance.";
    return (cause == null) ? new ApplicationContextException(msg)
        : new ApplicationContextException(msg, cause);
  }

  static String sanitizeUrl(String jdbcUrl) {
    if (jdbcUrl == null) {
      return "<unset>";
    }
    String stripped = jdbcUrl;
    String prefix = "";
    if (stripped.startsWith("jdbc:")) {
      stripped = stripped.substring("jdbc:".length());
      prefix = "jdbc:";
    }
    try {
      URI parsed = new URI(stripped);
      URI rebuilt =
          new URI(
              parsed.getScheme(),
              null,
              parsed.getHost(),
              parsed.getPort(),
              parsed.getPath(),
              parsed.getQuery(),
              null);
      return prefix + rebuilt.toString();
    } catch (URISyntaxException ex) {
      return prefix + "<unparseable-url>";
    }
  }
}
