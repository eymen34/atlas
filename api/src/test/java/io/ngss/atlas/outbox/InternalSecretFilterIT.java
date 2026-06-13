package io.ngss.atlas.outbox;

import static io.restassured.RestAssured.given;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-2 / SEC-2: the {@code /internal/tasks/drain-outbox} shared-secret gate. No header → 403;
 * wrong secret → 403; correct secret → 200. A non-drain {@code /internal/**} path stays 403
 * (denyAll preserved). A valid JWT (no secret) cannot reach the internal surface. And the filter
 * does NOT block {@code /api/**}.
 */
class InternalSecretFilterIT extends OutboxITBase {

  @Test
  void noSecretHeaderReturns403() {
    drainOutbox(null).then().statusCode(403);
  }

  @Test
  void wrongSecretReturns403() {
    drainOutbox("not-the-secret").then().statusCode(403);
  }

  @Test
  void correctSecretReturns200WithDrainResult() {
    drainOutbox(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("processed", org.hamcrest.Matchers.equalTo(0))
        .body("succeeded", org.hamcrest.Matchers.equalTo(0))
        .body("failed", org.hamcrest.Matchers.equalTo(0))
        .body("retried", org.hamcrest.Matchers.equalTo(0));
  }

  @Test
  void nonDrainInternalPathStaysForbidden() {
    given()
        .header("X-Internal-Secret", DRAIN_SECRET)
        .get("/internal/anything")
        .then()
        .statusCode(403); // only POST /internal/tasks/drain-outbox is granted to ROLE_INTERNAL
  }

  @Test
  void validJwtWithoutSecretCannotDrain() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    given()
        .header("Authorization", "Bearer " + token)
        .post("/internal/tasks/drain-outbox")
        .then()
        .statusCode(403); // JWT grants no ROLE_INTERNAL
  }

  @Test
  void apiRoutesAreUnaffectedByTheInternalFilter() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/projects")
        .then()
        .statusCode(200); // /api/** flows through normally
  }
}
