package io.ngss.atlas.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.activity.ActivityEventWriter;
import io.ngss.atlas.attachment.dto.InitUploadRequest;
import io.ngss.atlas.domain.Attachment;
import io.ngss.atlas.domain.Ticket;
import io.ngss.atlas.domain.TicketRepository;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Unit tests for {@link AttachmentService}'s init-time validation (no container needed)
 * and the filename sanitizer (T-025). The happy upload path is covered by the MinIO ITs.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID TICKET = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();

  @Mock AttachmentRepository attachmentRepository;
  @Mock TicketRepository ticketRepository;
  @Mock ProjectAccessGuard guard;
  @Mock ActivityEventWriter activityWriter;
  @Mock ApplicationEventPublisher eventPublisher;
  @Mock S3Client s3Client;
  @Mock S3Presigner s3Presigner;

  // maxSizeBytes = 100 so a 101-byte claim is oversize.
  private final ObjectStorageProperties props =
      new ObjectStorageProperties("http://s3", "http://s3", "us-east-1", "bucket", "ak", "sk", 100L);

  private AttachmentService service() {
    return new AttachmentService(
        attachmentRepository,
        ticketRepository,
        guard,
        activityWriter,
        props,
        eventPublisher,
        s3Client,
        s3Presigner);
  }

  private void ticketExists() {
    Ticket ticket = mock(Ticket.class);
    lenient().when(ticket.getProjectId()).thenReturn(PROJECT);
    when(ticketRepository.findById(TICKET)).thenReturn(Optional.of(ticket));
  }

  @Test
  void init_oversizeClaim_throws_andSavesNoRow() {
    ticketExists();
    AttachmentService service = service();

    assertThatThrownBy(
            () ->
                service.init(
                    TICKET, new InitUploadRequest("big.pdf", "application/pdf", 101), CALLER))
        .isInstanceOf(AttachmentValidationException.class);

    verify(attachmentRepository, never()).save(any(Attachment.class));
  }

  @Test
  void init_disallowedContentType_throws_andSavesNoRow() {
    ticketExists();
    AttachmentService service = service();

    assertThatThrownBy(
            () ->
                service.init(
                    TICKET, new InitUploadRequest("x.exe", "application/x-msdownload", 10), CALLER))
        .isInstanceOf(AttachmentValidationException.class);

    verify(attachmentRepository, never()).save(any(Attachment.class));
  }

  @Test
  void sanitizeFilename_stripsUnsafeChars_andFallsBackWhenEmpty() {
    assertThat(AttachmentService.sanitizeFilename("../../etc/passwd")).doesNotContain("/");
    assertThat(AttachmentService.sanitizeFilename("my report (final).pdf"))
        .isEqualTo("my_report__final_.pdf");
    assertThat(AttachmentService.sanitizeFilename("///")).isEqualTo("file");
    assertThat(AttachmentService.sanitizeFilename(null)).isEqualTo("file");
  }
}
