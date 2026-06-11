package io.ngss.atlas.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.activity.payload.CommentAddedPayload;
import io.ngss.atlas.activity.payload.CommentDeletedPayload;
import io.ngss.atlas.activity.payload.CommentEditedPayload;
import io.ngss.atlas.comment.dto.CommentResponse;
import io.ngss.atlas.comment.dto.CreateCommentRequest;
import io.ngss.atlas.comment.dto.UpdateCommentRequest;
import io.ngss.atlas.domain.ActivityEventType;
import io.ngss.atlas.domain.Comment;
import io.ngss.atlas.domain.CommentMention;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.mention.MentionParser;
import io.ngss.atlas.project.ForbiddenProjectAccessException;
import io.ngss.atlas.security.ProjectAccessGuard;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CommentService} (T-022). Drives the service directly (per
 * event_recorder_same_thread) so the atomic activity write can be asserted with an
 * {@link ArgumentCaptor} in the same call stack, and covers the author/admin
 * authorization branches.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID TICKET = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final UUID ALICE = UUID.randomUUID();

  @Mock CommentRepository commentRepository;
  @Mock CommentMentionRepository commentMentionRepository;
  @Mock TicketRepository ticketRepository;
  @Mock ProjectAccessGuard guard;
  @Mock MentionParser mentionParser;
  @Mock ActivityEventWriter activityWriter;
  @Mock EntityManager entityManager;
  @Mock io.ngss.atlas.watcher.WatcherService watcherService;
  @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
  @InjectMocks CommentService service;

  private Ticket ticketInProject() {
    Ticket ticket = mock(Ticket.class);
    lenient().when(ticket.getProjectId()).thenReturn(PROJECT);
    return ticket;
  }

  private Comment liveComment(UUID id, UUID authorId) {
    Comment comment = mock(Comment.class);
    lenient().when(comment.getId()).thenReturn(id);
    lenient().when(comment.getAuthorId()).thenReturn(authorId);
    lenient().when(comment.getTicketId()).thenReturn(TICKET);
    return comment;
  }

  @Test
  void create_persistsComment_savesMentions_andWritesCommentAddedAtomically() {
    Ticket ticket = ticketInProject();
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.of(ticket));
    when(mentionParser.parse("<p>@alice</p>", PROJECT)).thenReturn(Set.of(ALICE));

    CommentResponse response =
        service.create(TICKET, new CreateCommentRequest("<p>@alice</p>"), CALLER);

    verify(guard).requireMember(PROJECT);

    ArgumentCaptor<Comment> commentCaptor = ArgumentCaptor.forClass(Comment.class);
    verify(commentRepository).save(commentCaptor.capture());
    Comment saved = commentCaptor.getValue();
    assertThat(saved.getBody()).isEqualTo("<p>@alice</p>");
    assertThat(saved.getAuthorId()).isEqualTo(CALLER);
    assertThat(saved.getTicketId()).isEqualTo(TICKET);

    ArgumentCaptor<CommentMention> mentionCaptor = ArgumentCaptor.forClass(CommentMention.class);
    verify(commentMentionRepository).save(mentionCaptor.capture());
    assertThat(mentionCaptor.getValue().getUserId()).isEqualTo(ALICE);

    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(activityWriter)
        .record(
            eq(TICKET),
            eq(CALLER),
            eq(ActivityEventType.COMMENT_ADDED),
            payloadCaptor.capture(),
            any(Instant.class));
    assertThat(payloadCaptor.getValue())
        .isInstanceOfSatisfying(
            CommentAddedPayload.class, p -> assertThat(p.commentId()).isEqualTo(saved.getId()));

    assertThat(response.mentionedUserIds()).containsExactly(ALICE);
    assertThat(response.deleted()).isFalse();
  }

  @Test
  void update_byNonAuthorNonAdmin_throwsForbidden_andMutatesNothing() {
    UUID commentId = UUID.randomUUID();
    Comment comment = liveComment(commentId, OTHER);
    Ticket ticket = ticketInProject();
    when(commentRepository.findByIdAndDeletedAtIsNull(commentId)).thenReturn(Optional.of(comment));
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.of(ticket));
    when(guard.isAdmin(PROJECT)).thenReturn(false);

    assertThatThrownBy(() -> service.update(commentId, new UpdateCommentRequest("<p>x</p>"), CALLER))
        .isInstanceOf(ForbiddenProjectAccessException.class);

    verify(commentRepository, never()).save(any());
    verify(activityWriter, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void update_byAdminNonAuthor_succeeds_andWritesCommentEdited() {
    UUID commentId = UUID.randomUUID();
    Comment comment = liveComment(commentId, OTHER);
    Ticket ticket = ticketInProject();
    when(commentRepository.findByIdAndDeletedAtIsNull(commentId)).thenReturn(Optional.of(comment));
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.of(ticket));
    when(guard.isAdmin(PROJECT)).thenReturn(true);
    when(mentionParser.parse(any(), eq(PROJECT))).thenReturn(Set.of());

    service.update(commentId, new UpdateCommentRequest("<p>edited</p>"), CALLER);

    verify(commentMentionRepository).deleteByCommentId(commentId);
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(activityWriter)
        .record(
            eq(TICKET),
            eq(CALLER),
            eq(ActivityEventType.COMMENT_EDITED),
            payloadCaptor.capture(),
            any(Instant.class));
    assertThat(payloadCaptor.getValue())
        .isInstanceOfSatisfying(
            CommentEditedPayload.class, p -> assertThat(p.commentId()).isEqualTo(commentId));
  }

  @Test
  void softDelete_byAuthor_clearsMentions_andWritesCommentDeleted() {
    UUID commentId = UUID.randomUUID();
    Comment comment = liveComment(commentId, CALLER);
    Ticket ticket = ticketInProject();
    when(commentRepository.findByIdAndDeletedAtIsNull(commentId)).thenReturn(Optional.of(comment));
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.of(ticket));

    service.softDelete(commentId, CALLER);

    verify(commentMentionRepository).deleteByCommentId(commentId);
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(activityWriter)
        .record(
            eq(TICKET),
            eq(CALLER),
            eq(ActivityEventType.COMMENT_DELETED),
            payloadCaptor.capture(),
            any(Instant.class));
    assertThat(payloadCaptor.getValue())
        .isInstanceOfSatisfying(
            CommentDeletedPayload.class, p -> assertThat(p.commentId()).isEqualTo(commentId));
  }
}
