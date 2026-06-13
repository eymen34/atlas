package io.ngss.atlas.outbox;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Routes an {@link OutboxRow} to the {@link OutboxHandler} for its {@link OutboxKind} (T-029).
 * Built once from the injected handler beans; a duplicate registration for a kind fails fast at
 * startup. A lookup for a kind with no registered handler (e.g. the reserved-but-never-written
 * {@code ATTACHMENT_THUMBNAIL}) throws — surfacing as a retry/FAILED rather than a silent drop.
 */
@Component
public class OutboxDispatcher {

  private final Map<OutboxKind, OutboxHandler> byKind;

  public OutboxDispatcher(List<OutboxHandler> handlers) {
    EnumMap<OutboxKind, OutboxHandler> map = new EnumMap<>(OutboxKind.class);
    for (OutboxHandler handler : handlers) {
      OutboxHandler existing = map.putIfAbsent(handler.kind(), handler);
      if (existing != null) {
        throw new IllegalStateException(
            "duplicate OutboxHandler registered for kind " + handler.kind());
      }
    }
    this.byKind = Map.copyOf(map);
  }

  public OutboxHandler handlerFor(OutboxKind kind) {
    OutboxHandler handler = byKind.get(kind);
    if (handler == null) {
      throw new IllegalStateException("no OutboxHandler registered for kind " + kind);
    }
    return handler;
  }
}
