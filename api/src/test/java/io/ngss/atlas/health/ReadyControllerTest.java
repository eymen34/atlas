package io.ngss.atlas.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.annotation.PreDestroy;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ReadyControllerTest {

  private static DataSource happyDataSource() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    lenient().when(ds.getConnection()).thenReturn(c);
    lenient().when(c.prepareStatement(anyString())).thenReturn(ps);
    lenient().when(ps.executeQuery()).thenReturn(rs);
    return ds;
  }

  @Test
  void happyPathSetsQueryTimeoutOneAndReturnsReady() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(ds.getConnection()).thenReturn(c);
    when(c.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);

    ReadyController controller = new ReadyController(ds);
    ResponseEntity<Map<String, String>> response = controller.ready();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).containsExactly(Map.entry("status", "READY"));
    verify(ps).setQueryTimeout(1);
  }

  @Test
  void slowProbeIsCutOffByCallerBudget() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ds.getConnection()).thenReturn(c);
    when(c.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery())
        .thenAnswer(
            inv -> {
              Thread.sleep(600);
              return mock(ResultSet.class);
            });

    ReadyController controller = new ReadyController(ds);
    ResponseEntity<Map<String, String>> response =
        assertTimeout(Duration.ofMillis(1500), controller::ready);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).containsExactly(Map.entry("status", "NOT_READY"));
  }

  @Test
  void sqlExceptionFromGetConnectionReturnsNotReady() throws SQLException {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("server gone"));

    ReadyController controller = new ReadyController(ds);
    ResponseEntity<Map<String, String>> response = controller.ready();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).containsExactly(Map.entry("status", "NOT_READY"));
  }

  @Test
  void runtimeExceptionFromGetConnectionReturnsNotReady() throws SQLException {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new IllegalStateException("pool closed"));

    ReadyController controller = new ReadyController(ds);
    ResponseEntity<Map<String, String>> response = controller.ready();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).containsExactly(Map.entry("status", "NOT_READY"));
  }

  @Test
  void cancelOnTimeoutInterruptsWorker() throws SQLException, InterruptedException {
    AtomicBoolean interrupted = new AtomicBoolean(false);
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ds.getConnection()).thenReturn(c);
    when(c.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery())
        .thenAnswer(
            inv -> {
              try {
                Thread.sleep(Long.MAX_VALUE);
              } catch (InterruptedException ex) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", ex);
              }
              return mock(ResultSet.class);
            });

    ReadyController controller = new ReadyController(ds);
    ResponseEntity<Map<String, String>> response = controller.ready();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

    long deadline = System.currentTimeMillis() + 1500;
    while (!interrupted.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertThat(interrupted)
        .as("ready-probe worker thread should have received an interrupt after the timeout")
        .isTrue();
  }

  @Test
  void preDestroyAllowsSubsequentReadyToReturnNotReadyGracefully() throws Exception {
    DataSource ds = happyDataSource();
    ReadyController controller = new ReadyController(ds);

    Method shutdown = ReadyController.class.getDeclaredMethod("shutdown");
    boolean hasPreDestroy = shutdown.isAnnotationPresent(PreDestroy.class);
    assertThat(hasPreDestroy).as("@PreDestroy must be present on shutdown()").isTrue();
    shutdown.setAccessible(true);
    shutdown.invoke(controller);

    assertThatCode(
            () -> {
              ResponseEntity<Map<String, String>> response = controller.ready();
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(response.getBody()).containsExactly(Map.entry("status", "NOT_READY"));
            })
        .doesNotThrowAnyException();
  }
}
