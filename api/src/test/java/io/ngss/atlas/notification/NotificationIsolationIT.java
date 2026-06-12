package io.ngss.atlas.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ngss.atlas.domain.Notification;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Fault isolation (T-024, gate #4 / D2): a fan-out failure must NOT fail the originating
 * HTTP request — the core write commits, the notification is simply absent, and the
 * failure is logged at ERROR. We force the real {@link NotificationEventListener}'s insert
 * to throw via {@code @MockitoSpyBean} (Spring Framework 7's Boot-4 replacement for the
 * removed {@code @SpyBean}, offered by the architect as the alternative to an extra throwing
 * listener — which would propagate out of Spring's uncaught {@code invokeAfterCommit} loop
 * and 500 the request). The handler's own {@code try/catch(Exception)} swallows the mock's
 * RuntimeException and logs it; the AFTER_COMMIT timing means the ticket already committed,
 * so the request returns 201 regardless.
 */
class NotificationIsolationIT extends NotificationITBase {

  @MockitoSpyBean NotificationRepository notificationRepository;

  @Test
  void aFailingFanOutDoesNotFailTheRequest_writesNoRow_andLogsError() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");

    // Blow up the fan-out INSERT inside the real AFTER_COMMIT handler (plain RuntimeException
    // before any JDBC, so the REQUIRES_NEW tx is never marked rollback-only — the handler's
    // catch swallows it cleanly).
    doThrow(new RuntimeException("boom: simulated fan-out failure"))
        .when(notificationRepository)
        .save(any(Notification.class));

    Logger listenerLogger = (Logger) LoggerFactory.getLogger(NotificationEventListener.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    listenerLogger.addAppender(appender);
    try {
      // createTicket asserts HTTP 201 internally → the request did NOT fail despite the throw.
      createTicket(tokenAlice, eng, "{\"title\":\"T\",\"assigneeId\":\"" + bob + "\"}");
    } finally {
      listenerLogger.detachAppender(appender);
    }

    // Notification ABSENT (the insert threw) — but the ticket write was unaffected.
    assertThat(countByKindAndUser("ASSIGNED", bob)).isZero();

    boolean errorLogged = appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR);
    assertThat(errorLogged)
        .as("the handler's try/catch must log the failed fan-out at ERROR")
        .isTrue();
  }
}
