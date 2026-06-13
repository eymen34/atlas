package io.ngss.atlas.search;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Injection safety (T-028, AC4): adversarial inputs are bound via plainto_tsquery(:q),
 * so each returns 200 (a syntax-injection would surface as a 500/SQLException). A
 * ListAppender on the root logger confirms no SQLException is logged for any payload.
 */
class TicketSearchInjectionIT extends SearchITBase {

  @Test
  void injectionPayloadsReturn200WithNoSqlError() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");

    String[] payloads = {
      "'", "&", ":*", "!", "<script>alert(1)</script>", "A".repeat(5000)
    };

    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      for (String q : payloads) {
        searchProject(token, eng, q).then().statusCode(200);
        searchGlobal(token, q).then().statusCode(200);
      }
    } finally {
      root.detachAppender(appender);
    }

    boolean sqlErrorLogged = appender.list.stream().anyMatch(TicketSearchInjectionIT::mentionsSqlException);
    assertThat(sqlErrorLogged)
        .as("no SQLException should be logged for any injection payload")
        .isFalse();
  }

  private static boolean mentionsSqlException(ILoggingEvent event) {
    IThrowableProxy proxy = event.getThrowableProxy();
    while (proxy != null) {
      if (proxy.getClassName() != null && proxy.getClassName().contains("SQLException")) {
        return true;
      }
      proxy = proxy.getCause();
    }
    return false;
  }
}
