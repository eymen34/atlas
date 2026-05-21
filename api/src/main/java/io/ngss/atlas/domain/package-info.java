// Domain entities arrive in subsequent tickets. Hibernate may log a WARN
// (HHH90000003 / HHH90000025) about zero persistent classes during Sprint 0
// boot; this is expected and acceptable until the first @Entity lands.
package io.ngss.atlas.domain;
