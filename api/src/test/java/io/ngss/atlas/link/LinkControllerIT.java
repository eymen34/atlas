package io.ngss.atlas.link;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.response.Response;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** HTTP-level coverage of the ticket-link endpoints (T-026). */
class LinkControllerIT extends LinkITBase {

  @Autowired EntityManagerFactory entityManagerFactory;

  private record Fixture(String token, String eng, TicketRef t1, TicketRef t2) {}

  /** alice (ADMIN) + project ENG + two tickets ENG-1, ENG-2. */
  private Fixture seed() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    TicketRef t1 = createTicket(token, eng, "First");
    TicketRef t2 = createTicket(token, eng, "Second");
    return new Fixture(token, eng, t1, t2);
  }

  @Test
  void createBlocks_insertsBothReciprocalRows_andTwoLinkAddedActivityRows() {
    Fixture f = seed();

    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS")
        .then()
        .statusCode(201)
        .body("relation", equalTo("BLOCKS"))
        .body("targetTicketKey", equalTo("ENG-2"))
        .body("targetTitle", equalTo("Second"))
        .body("targetDeleted", equalTo(false));

    assertThat(linkRowCount()).isEqualTo(2);
    assertThat(linkRelationCount("BLOCKS")).isEqualTo(1);
    assertThat(linkRelationCount("IS_BLOCKED_BY")).isEqualTo(1);
    assertThat(activityCount(f.t1().id(), "LINK_ADDED")).isEqualTo(1);
    assertThat(activityCount(f.t2().id(), "LINK_ADDED")).isEqualTo(1);
  }

  @Test
  void createRelatesTo_storesRelatesToOnBothRows() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-2", "RELATES_TO").then().statusCode(201);
    assertThat(linkRelationCount("RELATES_TO")).isEqualTo(2);
  }

  @Test
  void createDuplicates_storesInverseDuplicatedBy() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-2", "DUPLICATES").then().statusCode(201);
    assertThat(linkRelationCount("DUPLICATES")).isEqualTo(1);
    assertThat(linkRelationCount("IS_DUPLICATED_BY")).isEqualTo(1);
  }

  @Test
  void conflictMatrix_anyExistingPairLinkRejectsAllFourCases() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(201);

    // (a) exact duplicate, (b) cross-direction same relation,
    // (c) different relation same direction, (d) cross-direction different relation.
    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(409);
    createLink(f.token(), f.t2().id(), "ENG-1", "BLOCKS").then().statusCode(409);
    createLink(f.token(), f.t1().id(), "ENG-2", "RELATES_TO").then().statusCode(409);
    createLink(f.token(), f.t2().id(), "ENG-1", "RELATES_TO").then().statusCode(409);

    assertThat(linkRowCount()).isEqualTo(2); // still just the one pair
  }

  @Test
  void selfLink_rejectedWith400_andNoRows() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-1", "BLOCKS").then().statusCode(400);
    assertThat(linkRowCount()).isZero();
  }

  @Test
  void unknownKey_rejectedWith400() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-999", "BLOCKS").then().statusCode(400);
    createLink(f.token(), f.t1().id(), "not-a-key", "BLOCKS").then().statusCode(400);
    assertThat(linkRowCount()).isZero();
  }

  @Test
  void crossProjectKey_rejectedWith400() {
    Fixture f = seed();
    String ops = createProject(f.token(), "OPS", "Operations");
    createTicket(f.token(), ops, "Ops one"); // OPS-1 exists, different project

    createLink(f.token(), f.t1().id(), "OPS-1", "BLOCKS").then().statusCode(400);
    assertThat(linkRowCount()).isZero();
  }

  @Test
  void inverseRelationType_rejectedWith400_beforeAnyDbWrite() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-2", "IS_BLOCKED_BY").then().statusCode(400);
    createLink(f.token(), f.t1().id(), "ENG-2", "IS_DUPLICATED_BY").then().statusCode(400);
    assertThat(linkRowCount()).isZero();
  }

  @Test
  void delete_removesBothRows_writesTwoLinkRemoved_andSecondDeleteIs404() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(201);
    String linkId =
        listLinks(f.token(), f.t1().id())
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("[0].id");

    deleteLink(f.token(), linkId).then().statusCode(204);
    assertThat(linkRowCount()).isZero();
    assertThat(activityCount(f.t1().id(), "LINK_REMOVED")).isEqualTo(1);
    assertThat(activityCount(f.t2().id(), "LINK_REMOVED")).isEqualTo(1);

    deleteLink(f.token(), linkId).then().statusCode(404); // already gone
  }

  @Test
  void anyMemberMayDelete_regardlessOfCreator() {
    Fixture f = seed();
    UUID bob = register("bob@example.com", "Bob");
    String bobToken = sign(bob);
    addMember(f.token(), f.eng(), "bob@example.com");
    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(201);
    String linkId =
        listLinks(f.token(), f.t1().id()).then().extract().jsonPath().getString("[0].id");

    deleteLink(bobToken, linkId).then().statusCode(204); // bob did not create it
    assertThat(linkRowCount()).isZero();
  }

  @Test
  void list_returnsBareArrayEnriched_andKeepsSoftDeletedTargetWithFlag() {
    Fixture f = seed();
    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(201);

    // Soft-delete the target ticket directly; the link row STAYS (not filtered).
    jdbc.update("UPDATE tickets SET deleted_at = now() WHERE id = ?::uuid", f.t2().id());

    listLinks(f.token(), f.t1().id())
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].relation", equalTo("BLOCKS"))
        .body("[0].targetTicketKey", equalTo("ENG-2"))
        .body("[0].targetDeleted", equalTo(true));
  }

  @Test
  void nonMember_gets404_onAllEndpoints() {
    Fixture f = seed();
    UUID eve = register("eve@example.com", "Eve"); // never a member
    String eveToken = sign(eve);
    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(201);
    String linkId =
        listLinks(f.token(), f.t1().id()).then().extract().jsonPath().getString("[0].id");

    listLinks(eveToken, f.t1().id()).then().statusCode(404);
    createLink(eveToken, f.t1().id(), "ENG-2", "RELATES_TO").then().statusCode(404);
    deleteLink(eveToken, linkId).then().statusCode(404);
  }

  @Test
  void allEndpointsRequireAuthentication() {
    String ticket = UUID.randomUUID().toString();
    String link = UUID.randomUUID().toString();
    given().get("/api/tickets/" + ticket + "/links").then().statusCode(401);
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body("{\"toTicketKey\":\"ENG-2\",\"relation\":\"BLOCKS\"}")
        .post("/api/tickets/" + ticket + "/links")
        .then()
        .statusCode(401);
    given().delete("/api/links/" + link).then().statusCode(401);
  }

  @Test
  void list_doesNotN1_asLinkCountGrows() {
    Fixture f = seed();
    TicketRef t3 = createTicket(f.token(), f.eng(), "Third");
    TicketRef t4 = createTicket(f.token(), f.eng(), "Fourth");
    TicketRef t5 = createTicket(f.token(), f.eng(), "Fifth");

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

    createLink(f.token(), f.t1().id(), "ENG-2", "BLOCKS").then().statusCode(201);
    stats.clear();
    listLinks(f.token(), f.t1().id()).then().statusCode(200).body("size()", equalTo(1));
    long withOne = stats.getPrepareStatementCount();

    createLink(f.token(), f.t1().id(), "ENG-3", "RELATES_TO").then().statusCode(201);
    createLink(f.token(), f.t1().id(), "ENG-4", "RELATES_TO").then().statusCode(201);
    createLink(f.token(), f.t1().id(), "ENG-5", "RELATES_TO").then().statusCode(201);
    stats.clear();
    listLinks(f.token(), f.t1().id()).then().statusCode(200).body("size()", equalTo(4));
    long withFour = stats.getPrepareStatementCount();

    // Enrichment is batched (links + targets-IN + one project) — the statement count
    // does NOT scale with the number of links. (t3/t4/t5 referenced to avoid unused.)
    assertThat(t3).isNotNull();
    assertThat(t4).isNotNull();
    assertThat(t5).isNotNull();
    assertThat(withFour).isEqualTo(withOne);
  }
}
