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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 403 JSON for authenticated requests that fail authorization (e.g. valid
 * Bearer token + GET /internal/anything). Same MDC requestId + X-Request-Id
 * mirror as {@link JsonAuthenticationEntryPoint}.
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    response.setHeader(RequestIdFilter.HEADER, requestId);
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "FORBIDDEN");
    body.put("message", "Access denied");
    body.put("requestId", requestId);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
