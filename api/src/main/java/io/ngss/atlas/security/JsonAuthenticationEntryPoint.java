package io.ngss.atlas.security;

import io.ngss.atlas.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Returns 401 JSON with the request correlation id for unauthenticated
 * requests to protected routes. Reads {@link RequestIdFilter#MDC_KEY} from
 * SLF4J MDC; falls back to a fresh UUID if missing (the filter has
 * HIGHEST_PRECEDENCE so MDC is normally populated by the time this runs).
 * Mirrors the id back as the {@code X-Request-Id} response header so the
 * SecurityIT body.requestId==header assertion holds.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    response.setHeader(RequestIdFilter.HEADER, requestId);
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "UNAUTHORIZED");
    body.put("message", "Authentication required");
    body.put("requestId", requestId);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
