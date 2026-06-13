package io.ngss.atlas.domain;

/**
 * The relation type of a {@link TicketLink} (T-026). Persisted as a string via
 * {@code @Enumerated(EnumType.STRING)} (text + CHECK column in V13), mirroring
 * {@link AttachmentStatus} / {@link NotificationKind}.
 *
 * <p>Five STORED types; only three are USER-FACING (accepted on create). Each create
 * writes the user-facing relation on the from→to row and its {@link #inverse} on the
 * to→from row. {@code RELATES_TO} is its own inverse. The inverse types
 * ({@code IS_BLOCKED_BY}, {@code IS_DUPLICATED_BY}) are server-derived only — the API
 * rejects them on create with 400.
 */
public enum LinkRelation {
  BLOCKS,
  IS_BLOCKED_BY,
  DUPLICATES,
  IS_DUPLICATED_BY,
  RELATES_TO;

  /** The relation stored on the reciprocal (to→from) row. */
  public static LinkRelation inverse(LinkRelation relation) {
    return switch (relation) {
      case BLOCKS -> IS_BLOCKED_BY;
      case IS_BLOCKED_BY -> BLOCKS;
      case DUPLICATES -> IS_DUPLICATED_BY;
      case IS_DUPLICATED_BY -> DUPLICATES;
      case RELATES_TO -> RELATES_TO;
    };
  }

  /** True for the relations a client may request on create (BLOCKS/DUPLICATES/RELATES_TO). */
  public static boolean isUserFacing(LinkRelation relation) {
    return relation == BLOCKS || relation == DUPLICATES || relation == RELATES_TO;
  }
}
