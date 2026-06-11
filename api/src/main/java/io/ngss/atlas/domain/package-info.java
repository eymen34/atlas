// Domain layer. Holds the project's 15 JPA entities (User, PasswordCredential —
// T-011; RefreshToken — T-012; Project — T-014; ProjectMember — T-015; Ticket +
// ProjectTicketCounter — T-017; Label + TicketLabel — T-018; ActivityEvent —
// T-019; Comment + CommentMention + TicketMention — T-022; TicketWatcher — T-023;
// Notification — T-024),
// most of their Spring Data repositories (the Label/TicketLabel and ActivityEvent
// repositories live in io.ngss.atlas.label / io.ngss.atlas.activity, the
// Comment/mention repositories in io.ngss.atlas.comment, and WatcherRepository in
// io.ngss.atlas.watcher, alongside those aggregates' services), the
// EmailAlreadyRegisteredException, and RegistrationService.
//
// APPCDS COLD-START HARD RULE (N6 / appcds_boot_safety): every @Entity here is
// initialized by the EntityManagerFactory during the Dockerfile stage-3 no-DB
// AppCDS boot. To keep that boot DB-free and deterministic, all entities MUST:
//   * assign their @Id in application code (UUID.randomUUID()) — NO
//     @GeneratedValue (identity/sequence generators probe DB metadata at EMF
//     initialization, which would require a live database);
//   * use plain UUID FK columns, NO @ManyToOne/@OneToMany associations;
//   * map enums with @Enumerated(EnumType.STRING) only (no DB enum types);
//   * use no custom Hibernate types and no JSON/array columns.
// Violating this turns the docker-build CI job red.
package io.ngss.atlas.domain;
