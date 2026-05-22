package io.ngss.atlas.health;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness probe. Runs a SELECT 1 against the configured DataSource with a
 * two-level guard: (1) JDBC ceiling via PreparedStatement.setQueryTimeout(1)
 * second, (2) caller-side budget via CompletableFuture.get(500, MILLISECONDS).
 * If anything fails or times out the response is 503 NOT_READY; the controller
 * never propagates an exception.
 *
 * <p>The probe runs on a dedicated single-thread daemon executor named
 * "ready-probe" with a SynchronousQueue and an AbortPolicy. The combination
 * means that concurrent /ready calls during a backend outage cannot grow a
 * queue or saturate Tomcat — the second caller's submit is rejected, which
 * CompletableFuture surfaces as an exceptionally-completed future, and the
 * catch arm below returns 503 in ~0ms.
 */
@RestController
public class ReadyController {

  private static final Map<String, String> READY = Map.of("status", "READY");
  private static final Map<String, String> NOT_READY = Map.of("status", "NOT_READY");

  private final DataSource dataSource;
  private final ExecutorService probeExecutor;

  public ReadyController(DataSource dataSource) {
    this.dataSource = dataSource;
    this.probeExecutor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
              Thread t = new Thread(r, "ready-probe");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  @GetMapping("/ready")
  public ResponseEntity<Map<String, String>> ready() {
    // ExecutorService.submit() + plain Future is intentional: Future.cancel(true)
    // honors the mayInterruptIfRunning flag and propagates Thread.interrupt() to
    // the worker. CompletableFuture.cancel ignores that flag, so a hung query
    // would leave the probe thread spinning. The dual-guard contract requires
    // the interrupt to be delivered.
    Callable<Boolean> task = this::probe;
    Future<Boolean> future;
    try {
      future = probeExecutor.submit(task);
    } catch (RejectedExecutionException ex) {
      return notReady();
    }
    try {
      Boolean ok = future.get(500, TimeUnit.MILLISECONDS);
      return Boolean.TRUE.equals(ok) ? ready200() : notReady();
    } catch (TimeoutException ex) {
      future.cancel(true);
      return notReady();
    } catch (InterruptedException ex) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return notReady();
    } catch (ExecutionException | RuntimeException ex) {
      future.cancel(true);
      return notReady();
    }
  }

  private boolean probe() {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT 1")) {
      ps.setQueryTimeout(1);
      ps.executeQuery();
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private static ResponseEntity<Map<String, String>> ready200() {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(READY);
  }

  private static ResponseEntity<Map<String, String>> notReady() {
    return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(NOT_READY);
  }

  @PreDestroy
  void shutdown() {
    probeExecutor.shutdownNow();
  }
}
