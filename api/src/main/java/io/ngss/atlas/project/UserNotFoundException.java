package io.ngss.atlas.project;

/**
 * Thrown when adding a member by an email that matches no registered user.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(String message) {
    super(message);
  }
}
