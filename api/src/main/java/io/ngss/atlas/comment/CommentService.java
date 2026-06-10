package io.ngss.atlas.comment;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.activity.payload.CommentAddedPayload;
import io.ngss.atlas.activity.payload.CommentDeletedPayload;
import io.ngss.atlas.activity.payload.CommentEditedPayload;
import io.ngss.atlas.comment.dto.CommentResponse;
import io.ngss.atlas.comment.dto.CreateCommentRequest;
import io.ngss.atlas.comment.dto.UpdateCommentRequest;
import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.Comment;
import io.ngss.atlas.domain.CommentMention;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.mention.MentionParser;
import io.ngss.atlas.project.ForbiddenProjectAccessException;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.ngss.atlas.ticket.TicketNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Comment aggregate (T-022).
 *
 * <p>Authorization mirrors {@link io.ngss.atlas.ticket.TicketService}: every
 * comment is reached by LOADING its ticket and then guarding {@code
 * ticket.projectId} (non-member → 404). Edit/delete additionally require the caller
 * to be the author OR a project ADMIN (else 403). Mentions are re-derived
 * server-side by {@link MentionParser} on every create/edit — the client's mention
 * metadata is never trusted (D4). Each mutation writes its activity row
 * SYNCHRONOUSLY in the same transaction (activity_synchronous_writer).
 */
@Service
public class CommentService {

  private final CommentRepository commentRepository;
  private final CommentMentionRepository commentMentionRepository;
  private final TicketRepository ticketRepository;
  private final ProjectAccessGuard guard;
  private final MentionParser mentionParser;
  private final ActivityEventWriter activityWriter;
  private final EntityManager entityManager;

  public CommentService(
      CommentRepository commentRepository,
      CommentMentionRepository commentMentionRepository,
      TicketRepository ticketRepository,
      ProjectAccessGuard guard,
      MentionParser mentionParser,
      ActivityEventWriter activityWriter,
      EntityManager entityManager) {
    this.commentRepository = commentRepository;
    this.commentMentionRepository = commentMentionRepository;
    this.ticketRepository = ticketRepository;
    this.guard = guard;
    this.mentionParser = mentionParser;
    this.activityWriter = activityWriter;
    this.entityManager = entityManager;
  }

  @Transactional
  public CommentResponse create(UUID ticketId, CreateCommentRequest req, UUID callerId) {
    Ticket ticket = loadTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    Instant now = Instant.now();
    Comment comment =
        new Comment(UUID.randomUUID(), ticketId, callerId, req.body(), now, now, null);
    commentRepository.save(comment);

    Set<UUID> mentioned = mentionParser.parse(req.body(), ticket.getProjectId());
    saveMentions(comment.getId(), mentioned);

    activityWriter.record(
        ticketId,
        callerId,
        ActivityEventType.COMMENT_ADDED,
        new CommentAddedPayload(comment.getId()),
        now);
    return CommentResponse.from(comment, List.copyOf(mentioned));
  }

  @Transactional(readOnly = true)
  public PagedResponse<CommentResponse> list(UUID ticketId, int page, int size, UUID callerId) {
    Ticket ticket = loadTicket(ticketId);
    guard.requireMember(ticket.getProjectId());

    int clampedSize = Math.max(1, Math.min(100, size));
    int clampedPage = Math.max(0, page);
    Page<Comment> commentsPage =
        commentRepository.findByTicketIdOrderByCreatedAtDescIdDesc(
            ticketId, PageRequest.of(clampedPage, clampedSize));

    // Batch-load mentions for the whole page in ONE query (no N+1). Deleted comments
    // have no mention rows (cleared on delete) and are redacted by CommentResponse.
    List<UUID> pageCommentIds = commentsPage.getContent().stream().map(Comment::getId).toList();
    Map<UUID, List<UUID>> mentionsByComment =
        pageCommentIds.isEmpty()
            ? Map.of()
            : commentMentionRepository.findByCommentIdIn(pageCommentIds).stream()
                .collect(
                    Collectors.groupingBy(
                        CommentMention::getCommentId,
                        Collectors.mapping(CommentMention::getUserId, Collectors.toList())));

    return PagedResponse.from(
        commentsPage,
        c -> CommentResponse.from(c, mentionsByComment.getOrDefault(c.getId(), List.of())));
  }

  @Transactional
  public CommentResponse update(UUID commentId, UpdateCommentRequest req, UUID callerId) {
    Comment comment = loadLiveComment(commentId);
    Ticket ticket = loadTicket(comment.getTicketId());
    guard.requireMember(ticket.getProjectId());
    requireAuthorOrAdmin(comment, callerId, ticket.getProjectId());

    Instant now = Instant.now();
    comment.editBody(req.body(), now);
    commentRepository.save(comment);

    // Re-derive mentions from scratch: drop the old set, flush so the DELETE lands
    // before the re-INSERTs (else the UNIQUE(comment_id,user_id) can be violated),
    // then persist the new set.
    commentMentionRepository.deleteByCommentId(commentId);
    entityManager.flush();
    Set<UUID> mentioned = mentionParser.parse(req.body(), ticket.getProjectId());
    saveMentions(commentId, mentioned);

    // COMMENT_EDITED is written unconditionally (no-op-edit suppression is backlog).
    activityWriter.record(
        comment.getTicketId(),
        callerId,
        ActivityEventType.COMMENT_EDITED,
        new CommentEditedPayload(commentId),
        now);
    return CommentResponse.from(comment, List.copyOf(mentioned));
  }

  @Transactional
  public void softDelete(UUID commentId, UUID callerId) {
    // Already-deleted → findByIdAndDeletedAtIsNull empty → 404 (idempotency boundary).
    Comment comment = loadLiveComment(commentId);
    Ticket ticket = loadTicket(comment.getTicketId());
    guard.requireMember(ticket.getProjectId());
    requireAuthorOrAdmin(comment, callerId, ticket.getProjectId());

    Instant now = Instant.now();
    comment.softDelete(now);
    commentRepository.save(comment);
    commentMentionRepository.deleteByCommentId(commentId);

    activityWriter.record(
        comment.getTicketId(),
        callerId,
        ActivityEventType.COMMENT_DELETED,
        new CommentDeletedPayload(commentId),
        now);
  }

  // ───────────────────────── helpers ─────────────────────────

  private void saveMentions(UUID commentId, Set<UUID> userIds) {
    for (UUID userId : userIds) {
      commentMentionRepository.save(new CommentMention(commentId, userId));
    }
  }

  private void requireAuthorOrAdmin(Comment comment, UUID callerId, UUID projectId) {
    if (!comment.getAuthorId().equals(callerId) && !guard.isAdmin(projectId)) {
      throw new ForbiddenProjectAccessException(projectId);
    }
  }

  private Ticket loadTicket(UUID ticketId) {
    return ticketRepository
        .findById(ticketId)
        .orElseThrow(() -> new TicketNotFoundException("ticket not found: " + ticketId));
  }

  private Comment loadLiveComment(UUID commentId) {
    return commentRepository
        .findByIdAndDeletedAtIsNull(commentId)
        .orElseThrow(() -> new CommentNotFoundException("comment not found: " + commentId));
  }
}
