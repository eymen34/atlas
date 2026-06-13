package io.ngss.atlas.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.ngss.atlas.domain.Ticket;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DataIntegrityViolationException;

/** Project-scoped search: ranking, stemming, clamps, soft-delete, authz, generated column (T-028). */
class TicketSearchProjectIT extends SearchITBase {

  private record Fixture(UUID alice, String token, String eng) {}

  private Fixture seed() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    return new Fixture(alice, token, eng);
  }

  @Test
  void stemmingMatchesAndSnippetHasSentinels() {
    Fixture f = seed();
    createTicket(f.token(), f.eng(), "Authentication services", "login flow");
    createTicket(f.token(), f.eng(), "Billing dashboard", "invoices");

    // "service" stems to the same root as "services" → matches; the dashboard does not.
    searchProject(f.token(), f.eng(), "service")
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("items[0].ticketKey", equalTo("ENG-1"))
        .body("items[0].snippet", org.hamcrest.Matchers.containsString("[["));
  }

  @Test
  void equalRankTiesBreakByUpdatedAtDesc() {
    Fixture f = seed();
    TicketRef older = createTicket(f.token(), f.eng(), "payment service", "");
    TicketRef newer = createTicket(f.token(), f.eng(), "payment service", "");
    // Identical documents → identical rank; force distinct updated_at so the tie resolves.
    jdbc.update("UPDATE tickets SET updated_at = ?::timestamptz WHERE id = ?::uuid", "2026-01-01T00:00:00Z", older.id());
    jdbc.update("UPDATE tickets SET updated_at = ?::timestamptz WHERE id = ?::uuid", "2026-02-01T00:00:00Z", newer.id());

    searchProject(f.token(), f.eng(), "payment")
        .then()
        .statusCode(200)
        .body("total", equalTo(2))
        .body("items[0].ticketId", equalTo(newer.id()))
        .body("items[1].ticketId", equalTo(older.id()));
  }

  @Test
  void sizeIsClamped1to100() {
    Fixture f = seed();
    createTicket(f.token(), f.eng(), "service alpha", "");
    createTicket(f.token(), f.eng(), "service beta", "");

    searchProject(f.token(), f.eng(), "service", 0, 0).then().statusCode(200).body("size", equalTo(1));
    searchProject(f.token(), f.eng(), "service", 0, 500).then().statusCode(200).body("size", equalTo(100));
  }

  @Test
  void softDeletedTicketsAreExcluded() {
    Fixture f = seed();
    TicketRef t = createTicket(f.token(), f.eng(), "deployment service", "");
    jdbc.update("UPDATE tickets SET deleted_at = now() WHERE id = ?::uuid", t.id());

    searchProject(f.token(), f.eng(), "deployment").then().statusCode(200).body("total", equalTo(0));
  }

  @Test
  void nonMemberGets404() {
    Fixture f = seed();
    UUID carol = register("carol@example.com", "Carol"); // never a member of ENG
    String carolToken = sign(carol);

    searchProject(carolToken, f.eng(), "anything").then().statusCode(404);
  }

  @Test
  void whitespaceOnlyQueryReturns200Empty() {
    Fixture f = seed();
    createTicket(f.token(), f.eng(), "anything", "");
    searchProject(f.token(), f.eng(), "   ").then().statusCode(200).body("total", equalTo(0));
  }

  @Test
  void generatedColumnIsAutoPopulated_andRejectsExplicitInserts() {
    Fixture f = seed();
    TicketRef t = createTicket(f.token(), f.eng(), "indexed ticket", "body");
    assertThat(searchDoc(t.id())).isNotBlank(); // Postgres computed it

    // A raw INSERT that lists search_doc (a generated column) is rejected.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO tickets (id, project_id, number, title, status, priority, "
                        + "reporter_id, created_at, updated_at, search_doc) "
                        + "VALUES (?::uuid, ?::uuid, 9999, 'x', 'TODO', 'P2', ?::uuid, now(), now(), "
                        + "to_tsvector('english','x'))",
                    UUID.randomUUID().toString(),
                    f.eng(),
                    f.alice().toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void searchDocIsNotMappedOnTicketEntity_andEntityCountStays17() {
    assertThat(Arrays.stream(Ticket.class.getDeclaredFields()).map(Field::getName))
        .doesNotContain("searchDoc", "search_doc");

    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(jakarta.persistence.Entity.class));
    int entityCount = scanner.findCandidateComponents("io.ngss.atlas.domain").size();
    assertThat(entityCount).isEqualTo(17);
  }

  @Test
  void rankIsPresentAndNonNegative() {
    Fixture f = seed();
    createTicket(f.token(), f.eng(), "ranking service test", "");
    searchProject(f.token(), f.eng(), "ranking")
        .then()
        .statusCode(200)
        .body("items[0].rank", greaterThanOrEqualTo(0.0f));
  }
}
