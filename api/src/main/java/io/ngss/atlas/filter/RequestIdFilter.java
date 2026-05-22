package io.ngss.atlas.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves a request correlation id from the X-Request-Id header (or generates
 * a fresh UUIDv4 when the header is missing or fails validation), publishes it
 * to SLF4J MDC under the key {@code request_id}, mirrors it back as the
 * response X-Request-Id header, and clears MDC in a finally block. Runs at
 * HIGHEST_PRECEDENCE so the MDC entry is in place for every downstream filter
 * and controller log statement.
 *
 * <p>Security: the raw inbound X-Request-Id value is matched against a
 * conservative character-set pattern before it is allowed anywhere near MDC
 * or the response header. Rejected raw values are NEVER log-formatted. If a
 * future log message must reference a rejection event, log only the redacted
 * token {@code <invalid>} and the validated/generated id — never the raw
 * inbound bytes. This blocks CRLF injection (header smuggling), Log4Shell-
 * style {@code ${jndi:}} payloads, and HTML/JS payloads from reaching log
 * sinks via the correlation channel.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";
  static final String MDC_KEY = "request_id";
  static final Pattern REQUEST_ID = Pattern.compile("^[a-zA-Z0-9-]{1,128}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String id =
        (incoming != null && REQUEST_ID.matcher(incoming).matches())
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    // Async dispatches inherit the MDC from the original request thread via
    // Spring's TaskDecorator chain in DispatcherServlet; re-running this
    // filter on the async dispatch would overwrite the existing correlation
    // id with a fresh UUID.
    return true;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    // Error dispatches need a correlation id too — typically a 5xx page or an
    // error controller — so let the filter run.
    return false;
  }
}
