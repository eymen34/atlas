package io.ngss.atlas.outbox;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-2 / SEC: the {@code /internal/tasks/run-maintenance} shared-secret gate. A new internal
 * endpoint is NOT auto-authorized by the filter — SecurityConfig must grant ROLE_INTERNAL to its
 * exact path (it does, alongside drain-outbox), and every other case is a 403 (NOT a 401, via the
 * non-anonymous empty-authority token).
 */
class RunMaintenanceAuthIT extends OutboxITBase {

  @Test
  void noSecretHeader_returns403() {
    runMaintenance(null).then().statusCode(403); // exactly 403, not 401
  }

  @Test
  void blankSecret_returns403() {
    runMaintenance("").then().statusCode(403);
  }

  @Test
  void wrongSecret_returns403() {
    runMaintenance("not-the-secret").then().statusCode(403);
  }

  @Test
  void prefixMatchAttack_returns403() {
    // A longer string that merely starts with the secret must fail the constant-time compare.
    runMaintenance(DRAIN_SECRET + "-extra").then().statusCode(403);
  }

  @Test
  void validJwtWithoutSecret_returns403() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    given()
        .header("Authorization", "Bearer " + token)
        .post("/internal/tasks/run-maintenance")
        .then()
        .statusCode(403); // a JWT grants no ROLE_INTERNAL
  }

  @Test
  void correctSecretOnEmptyDb_returns200WithZeroedResult() {
    runMaintenance(DRAIN_SECRET)
        .then()
        .statusCode(200)
        .body("reclaimedToPending", equalTo(0))
        .body("reclaimedToFailed", equalTo(0))
        .body("expiredUploads", equalTo(0));
  }
}
