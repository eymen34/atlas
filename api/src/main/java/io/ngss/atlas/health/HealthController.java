package io.ngss.atlas.health;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Load-balancer-facing liveness probe. Returns 200 {"status":"UP"} immediately
 * without touching the DataSource. Intentionally distinct from Spring Boot
 * Actuator's /actuator/health, which aggregates DB and other health
 * indicators and is intended for operators (it touches the DB on every call
 * and can therefore amplify a DB outage into a probe storm). /health is the
 * cheap liveness signal a load balancer or Kubernetes liveness probe should
 * hit; /actuator/health is for humans and dashboards.
 */
@RestController
public class HealthController {

  private static final Map<String, String> UP = Map.of("status", "UP");

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(UP);
  }
}
