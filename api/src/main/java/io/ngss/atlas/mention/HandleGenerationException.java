package io.ngss.atlas.mention;

/**
 * Raised when {@link MentionHandleGenerator} cannot find a free handle within its
 * bounded suffix-attempt budget (T-022). The caller (registration) maps this to a
 * 409 — it is an astronomically rare contention signal, never a normal outcome.
 */
public class HandleGenerationException extends RuntimeException {
  public HandleGenerationException(String message) {
    super(message);
  }
}
