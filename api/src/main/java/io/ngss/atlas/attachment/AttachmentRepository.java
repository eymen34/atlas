package io.ngss.atlas.attachment;

import io.ngss.atlas.domain.Attachment;
import io.ngss.atlas.domain.AttachmentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link Attachment} (T-025). */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

  /** A live (non-soft-deleted) attachment by id — used by finalize/download/delete. */
  Optional<Attachment> findByIdAndDeletedAtIsNull(UUID id);

  /** The ticket's listable attachments: a given status, non-deleted, newest first. */
  List<Attachment> findByTicketIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
      UUID ticketId, AttachmentStatus status);
}
