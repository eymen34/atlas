package io.ngss.atlas.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStartupValidator implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(DatabaseStartupValidator.class);

  private final DataSource dataSource;
  private final String configuredUrl;
  private volatile boolean running = false;

  /**
   * Toggle for the fail-fast DB ping performed during context refresh.
   *
   * <p>Default: {@code true} — the validator opens a connection and runs SELECT 1, aborting
   * context refresh with an {@link ApplicationContextException} on failure. This is the only
   * supported setting for any deployment environment (dev, staging, production).
   *
   * <p>When {@code false}, {@link #start()} returns immediately without touching the DataSource.
   * This bypass exists EXCLUSIVELY for the AppCDS image-build stage of the production Dockerfile,
   * where the JVM is booted to the refresh phase ({@code -Dspring.context.exit=onRefresh}) without
   * a reachable database in order to warm a shared class-data archive. Setting this flag to
   * {@code false} anywhere else silently disables the fail-fast contract and will cause the
   * service to start against an unreachable or misconfigured database, surfacing as 5xx storms
   * once traffic arrives.
   *
   * <p>Current value is exposed via {@code /actuator/info} (key {@code databaseStartupCheck.enabled})
   * for runtime visibility so operators can confirm the toggle is {@code true} in deployed
   * environments.
   */
  @Value("${app.database.startup-check.enabled:true}")
  private boolean startupCheckEnabled = true;

  public DatabaseStartupValidator(DataSource dataSource, DatabaseProperties properties) {
    this.dataSource = dataSource;
    this.configuredUrl = properties.getUrl();
  }

  @Override
  public void start() {
    if (!startupCheckEnabled) {
      log.warn(
          "DatabaseStartupValidator DISABLED by app.database.startup-check.enabled=false. "
              + "This bypasses the fail-fast DB ping and is ONLY valid for the AppCDS "
              + "image-build stage. If you see this in a deployment environment, the "
              + "deployment is misconfigured.");
      running = true;
      return;
    }
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

  public boolean isStartupCheckEnabled() {
    return startupCheckEnabled;
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
