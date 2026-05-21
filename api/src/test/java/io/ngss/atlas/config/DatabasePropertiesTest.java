package io.ngss.atlas.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DatabasePropertiesTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void init() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @Test
  void rejectLibpqUrl() {
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("postgresql://user:pass@localhost:5432/atlas");
    Set<ConstraintViolation<DatabaseProperties>> violations = validator.validate(props);
    assertFalse(violations.isEmpty(), "libpq-style URL must produce a violation");
    assertTrue(
        violations.stream().anyMatch(v -> "url".equals(v.getPropertyPath().toString())),
        "violation must report path 'url'");
  }

  @Test
  void rejectBlankUrl() {
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("");
    Set<ConstraintViolation<DatabaseProperties>> violations = validator.validate(props);
    assertFalse(violations.isEmpty(), "blank URL must produce a violation");
    assertTrue(
        violations.stream().anyMatch(v -> "url".equals(v.getPropertyPath().toString())),
        "violation must report path 'url'");
  }

  @Test
  void trimsWhitespace() {
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("   jdbc:postgresql://localhost:5432/atlas   ");
    assertEquals("jdbc:postgresql://localhost:5432/atlas", props.getUrl());
  }

  @Test
  void acceptJdbcUrl() {
    DatabaseProperties props = new DatabaseProperties();
    props.setUrl("jdbc:postgresql://localhost:5432/atlas");
    Set<ConstraintViolation<DatabaseProperties>> violations = validator.validate(props);
    assertTrue(violations.isEmpty(), "valid JDBC URL must yield zero violations");
  }

  @Test
  void emptyPasswordDefault() {
    DatabaseProperties props = new DatabaseProperties();
    assertEquals("", props.getPassword());
  }
}
