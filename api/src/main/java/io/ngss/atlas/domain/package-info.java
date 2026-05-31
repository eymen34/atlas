// Domain layer. As of T-011 this package holds the project's FIRST JPA
// entities (User, PasswordCredential), their Spring Data repositories, the
// EmailAlreadyRegisteredException, and RegistrationService.
//
// APPCDS COLD-START HARD RULE (N6 / appcds_boot_safety): every @Entity here is
// initialized by the EntityManagerFactory during the Dockerfile stage-3 no-DB
// AppCDS boot. To keep that boot DB-free and deterministic, all entities MUST:
//   * assign their @Id in application code (UUID.randomUUID()) — NO
//     @GeneratedValue (identity/sequence generators probe DB metadata at EMF
//     initialization, which would require a live database);
//   * use no custom Hibernate types and no JSON/array columns.
// Violating this turns the docker-build CI job red.
package io.ngss.atlas.domain;
