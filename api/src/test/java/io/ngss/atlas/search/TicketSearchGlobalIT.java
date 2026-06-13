package io.ngss.atlas.search;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.response.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Global search: SQL-enforced project-membership isolation + param/auth edges (T-028, AC3). */
class TicketSearchGlobalIT extends SearchITBase {

  @Test
  void globalSearchExcludesTicketsInProjectsTheCallerIsNotAMemberOf() {
    UUID alice = register("alice@example.com", "Alice");
    UUID bob = register("bob@example.com", "Bob");
    String aliceToken = sign(alice);
    String bobToken = sign(bob);

    String eng = createProject(aliceToken, "ENG", "Engineering"); // alice's project
    String ops = createProject(bobToken, "OPS", "Operations"); // bob's project — alice NOT a member
    TicketRef t1 = createTicket(aliceToken, eng, "authentication service", "");
    TicketRef t2 = createTicket(bobToken, ops, "authentication service", ""); // matches the SAME query

    Response r = searchGlobal(aliceToken, "authentication");
    r.then().statusCode(200).body("total", equalTo(1));

    List<String> ids = r.jsonPath().getList("items.ticketId", String.class);
    assertThat(ids).contains(t1.id()).doesNotContain(t2.id()); // T2 absent — enforced in SQL
  }

  @Test
  void missingQParameterReturns400() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    given()
        .header("Authorization", "Bearer " + token)
        .get("/api/search/tickets")
        .then()
        .statusCode(400);
  }

  @Test
  void unauthenticatedRequestReturns401() {
    given().queryParam("q", "anything").get("/api/search/tickets").then().statusCode(401);
  }
}
