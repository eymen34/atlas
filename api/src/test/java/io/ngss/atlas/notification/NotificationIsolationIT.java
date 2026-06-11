package io.ngss.atlas.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ngss.atlas.ticket.event.TicketAssignedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fault isolation (T-024, gate #11): a second AFTER_COMMIT listener that throws must NOT
 * stop the real {@link NotificationEventListener} from writing its row, NOR fail the HTTP
 * request (the originating transaction already committed). We register an extra throwing
 * {@code @TransactionalEventListener(AFTER_COMMIT)} bean (architect-preferred over @SpyBean,
 * which Spring Framework 7 dropped) and assert the real ASSIGNED row still lands while the
 * framework logs the failure. The logger-name assertion is an {@code anyOf} over candidates
 * because Spring's exact adapter logger name is version-dependent.
 */
@Import(NotificationIsolationIT.FailingListenerConfig.class)
class NotificationIsolationIT extends NotificationITBase {

  @TestConfiguration
  static class FailingListenerConfig {
    @Bean
    BoomListener boomListener() {
      return new BoomListener();
    }

    static class BoomListener {
      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      public void boom(TicketAssignedEvent e) {
        throw new IllegalStateException("boom: simulated fan-out failure");
      }
    }
  }

  @Test
  void aThrowingAfterCommitListenerDoesNotPreventTheRealNotificationNorFailTheRequest() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String tokenAlice = sign(alice);
    String eng = createProject(tokenAlice, "ENG", "Engineering");
    addMember(tokenAlice, eng, "bob@example.com");

    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      // createTicket asserts HTTP 201 internally → request did NOT fail despite the boom.
      createTicket(tokenAlice, eng, "{\"title\":\"T\",\"assigneeId\":\"" + bob + "\"}");
    } finally {
      root.detachAppender(appender);
    }

    // Real listener still wrote its row — the throwing listener was isolated.
    assertThat(countByKindAndUser("ASSIGNED", bob)).isEqualTo(1);

    // anyOf candidate loggers captured the failure at WARN/ERROR.
    boolean failureLogged =
        appender.list.stream()
            .filter(e -> e.getLevel().toInt() >= Level.WARN.toInt())
            .anyMatch(
                e ->
                    e.getLoggerName().startsWith("org.springframework.transaction")
                        || e.getLoggerName().equals(NotificationEventListener.class.getName()));
    assertThat(failureLogged)
        .as("a WARN/ERROR from the transaction-event adapter (or the listener) should record the failed fan-out")
        .isTrue();
  }
}
