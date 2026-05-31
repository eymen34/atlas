package io.ngss.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextException;
import org.springframework.test.util.ReflectionTestUtils;

class DatabaseStartupValidatorTest {

  @Test
  void sanitizesCredentialsOnConnectionFailure() throws SQLException {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("Connection refused"));
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://admin:s3cr3t@192.0.2.1:5432/atlas");
    DatabaseStartupValidator validator = new DatabaseStartupValidator(ds, props);

    assertThatThrownBy(validator::start)
        .isInstanceOf(ApplicationContextException.class)
        .hasMessageNotContaining("s3cr3t")
        .hasMessageNotContaining("admin:")
        .hasMessageContaining("192.0.2.1");
  }

  @Test
  void doesNotLeakCredentialsInCauseChain() throws SQLException {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("Connection refused"));
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://dbuser:TopS3cr3t!@192.0.2.1:5432/atlas");
    DatabaseStartupValidator validator = new DatabaseStartupValidator(ds, props);

    assertThatThrownBy(validator::start)
        .isInstanceOf(ApplicationContextException.class)
        .satisfies(
            ex -> {
              Throwable t = ex;
              while (t != null) {
                String m = t.getMessage();
                if (m != null) {
                  assertThat(m).doesNotContain("TopS3cr3t");
                  assertThat(m).doesNotMatch(".*dbuser:[^@]*@.*");
                }
                t = t.getCause();
              }
            });
  }

  @Test
  void stopMakesNoSqlOrNetworkCalls() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.isValid(2)).thenReturn(true);
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://localhost:5432/atlas");
    DatabaseStartupValidator validator = new DatabaseStartupValidator(ds, props);

    validator.start();
    assertThat(validator.isRunning()).isTrue();

    org.mockito.Mockito.clearInvocations(ds, conn);
    validator.stop();

    assertThat(validator.isRunning()).isFalse();
    verifyNoInteractions(ds);
    verifyNoInteractions(conn);
  }

  @Test
  void sanitizeUrlStripsUserinfoButKeepsHost() {
    String sanitized =
        DatabaseStartupValidator.sanitizeUrl(
            "jdbc:postgresql://admin:s3cr3t@192.0.2.1:5432/atlas");
    assertThat(sanitized).doesNotContain("s3cr3t");
    assertThat(sanitized).doesNotContain("admin");
    assertThat(sanitized).contains("192.0.2.1");
    assertThat(sanitized).contains("5432");
    assertThat(sanitized).contains("/atlas");
    assertThat(sanitized).startsWith("jdbc:postgresql://");
  }

  @Test
  void sanitizeUrlHandlesNoUserinfo() {
    String sanitized =
        DatabaseStartupValidator.sanitizeUrl("jdbc:postgresql://localhost:5432/atlas");
    assertThat(sanitized).contains("localhost:5432/atlas");
  }

  @Test
  void start_invokesDataSourceWhenEnabled() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.isValid(2)).thenReturn(true);
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://localhost:5432/atlas");
    DatabaseStartupValidator validator = new DatabaseStartupValidator(ds, props);
    ReflectionTestUtils.setField(validator, "startupCheckEnabled", true);

    validator.start();

    verify(ds).getConnection();
    verify(conn).isValid(2);
    assertThat(validator.isRunning()).isTrue();
  }

  @Test
  void start_skipsDataSourceWhenDisabled() {
    DataSource ds = mock(DataSource.class);
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://cds-build-placeholder:5432/atlas");
    DatabaseStartupValidator validator = new DatabaseStartupValidator(ds, props);
    ReflectionTestUtils.setField(validator, "startupCheckEnabled", false);

    assertThatCode(validator::start).doesNotThrowAnyException();

    verifyNoInteractions(ds);
    assertThat(validator.isRunning()).isTrue();
  }

  @Test
  void isStartupCheckEnabled_reflectsField() {
    DataSource ds = mock(DataSource.class);
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://localhost:5432/atlas");
    DatabaseStartupValidator validator = new DatabaseStartupValidator(ds, props);

    ReflectionTestUtils.setField(validator, "startupCheckEnabled", true);
    assertThat(validator.isStartupCheckEnabled()).isTrue();

    ReflectionTestUtils.setField(validator, "startupCheckEnabled", false);
    assertThat(validator.isStartupCheckEnabled()).isFalse();
  }
}
