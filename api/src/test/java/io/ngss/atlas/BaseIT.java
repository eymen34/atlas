package io.ngss.atlas;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared integration-test support. Centralises the FK-ordered database teardown so
 * the delete order lives in ONE place — adding a new child table (the T-015 and
 * T-017 lesson, repeated for ticket_labels/labels in T-018) means updating only
 * this method, not every IT.
 *
 * <p>Exposed as a {@code static} utility (call {@code BaseIT.cleanDatabase(jdbc)})
 * so ITs do not need to change their class hierarchy or {@code @SpringBootTest}
 * configuration to adopt it.
 */
public abstract class BaseIT {

  protected BaseIT() {}

  /**
   * Deletes all domain rows in strict child → parent FK order. Every FK in the
   * schema is {@code ON DELETE NO ACTION} (no cascade), so each referencing table
   * MUST be emptied before the table it references:
   *
   * <pre>
   * ticket_labels → labels → tickets → project_ticket_counters → project_members
   *   → projects → refresh_tokens → password_credentials → users
   * </pre>
   *
   * <p>Tables with no rows for a given test (e.g. labels in an auth-only IT) delete
   * as harmless no-ops, so this is safe to call from every IT.
   */
  public static void cleanDatabase(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM ticket_labels");
    jdbc.update("DELETE FROM labels");
    jdbc.update("DELETE FROM tickets");
    jdbc.update("DELETE FROM project_ticket_counters");
    jdbc.update("DELETE FROM project_members");
    jdbc.update("DELETE FROM projects");
    jdbc.update("DELETE FROM refresh_tokens");
    jdbc.update("DELETE FROM password_credentials");
    jdbc.update("DELETE FROM users");
  }
}
