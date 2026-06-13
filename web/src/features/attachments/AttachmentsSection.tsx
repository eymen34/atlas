import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, Loader2, Trash2, Upload, X } from 'lucide-react';
import { useRef, useState } from 'react';
import { toast } from 'sonner';
import {
  type Attachment,
  attachmentKeys,
  deleteAttachment,
  finalizeAttachment,
  formatBytes,
  getAttachmentDownloadUrl,
  initAttachmentUpload,
  isImage,
  listAttachments,
  uploadToPresignedUrl,
} from '@/api/attachments';
import { apiErrorMessage } from '@/api/errors';
import { cn } from '@/lib/utils';

interface UploadTask {
  key: number;
  name: string;
  progress: number;
  status: 'uploading' | 'error';
  error?: string;
}

export interface AttachmentsSectionProps {
  /** Ticket UUID — attachment endpoints + cache key. */
  ticketId: string;
  currentUserId: string | null;
  isProjectAdmin: boolean;
}

/**
 * Ticket attachments (T-025). Drag-drop / click dropzone → init → direct XHR PUT to
 * the presigned URL (with per-file progress) → finalize → invalidate the list
 * (PRAGMATIC, frontend_mutation_strategy). READY images render as a thumbnail grid;
 * everything else as a file list. Row click fetches a fresh presigned GET and opens it.
 */
export function AttachmentsSection({
  ticketId,
  currentUserId,
  isProjectAdmin,
}: AttachmentsSectionProps) {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const nextKey = useRef(0);
  const [uploads, setUploads] = useState<UploadTask[]>([]);
  const [dragging, setDragging] = useState(false);

  const query = useQuery({
    queryKey: attachmentKeys.list(ticketId),
    queryFn: () => listAttachments(ticketId),
    enabled: !!ticketId,
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: attachmentKeys.list(ticketId) });
  }

  const deleteMut = useMutation({
    mutationFn: (id: string) => deleteAttachment(id),
    onSuccess: () => {
      invalidate();
      toast.success('Attachment deleted');
    },
    onError: () => toast.error('Could not delete the attachment'),
  });

  async function runUpload(file: File, key: number) {
    const contentType = file.type || 'application/octet-stream';
    try {
      const init = await initAttachmentUpload(ticketId, file.name, contentType, file.size);
      await uploadToPresignedUrl(init.uploadUrl, file, contentType, (percent) =>
        setUploads((prev) => prev.map((u) => (u.key === key ? { ...u, progress: percent } : u)))
      );
      await finalizeAttachment(init.attachmentId);
      setUploads((prev) => prev.filter((u) => u.key !== key));
      invalidate();
    } catch (err) {
      const message = apiErrorMessage(err, 'Upload failed');
      setUploads((prev) =>
        prev.map((u) => (u.key === key ? { ...u, status: 'error', error: message } : u))
      );
    }
  }

  function handleFiles(files: FileList | null) {
    if (!files) return;
    for (const file of Array.from(files)) {
      const key = nextKey.current++;
      setUploads((prev) => [...prev, { key, name: file.name, progress: 0, status: 'uploading' }]);
      void runUpload(file, key);
    }
  }

  async function openAttachment(a: Attachment, thumbnail = false) {
    try {
      const url = await getAttachmentDownloadUrl(a.id, thumbnail);
      window.open(url, '_blank', 'noopener');
    } catch {
      toast.error('Could not open the file');
    }
  }

  function canDelete(a: Attachment): boolean {
    return a.uploadedBy === currentUserId || isProjectAdmin;
  }

  const attachments = query.data ?? [];
  const images = attachments.filter((a) => isImage(a.contentType));
  const files = attachments.filter((a) => !isImage(a.contentType));

  return (
    <section data-testid="attachments-section" aria-label="Attachments" className="space-y-4">
      <h2 className="text-sm font-medium text-muted-foreground">Attachments</h2>

      <div
        data-testid="attachment-dropzone"
        role="button"
        tabIndex={0}
        aria-label="Upload attachments"
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            inputRef.current?.click();
          }
        }}
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          handleFiles(e.dataTransfer.files);
        }}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-1 rounded-md border border-dashed border-border px-4 py-6 text-sm text-muted-foreground transition-colors',
          dragging && 'border-primary bg-accent/40'
        )}
      >
        <Upload className="h-5 w-5" aria-hidden="true" />
        <span>Drag files here, or click to upload</span>
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          data-testid="attachment-input"
          onChange={(e) => {
            handleFiles(e.target.files);
            e.target.value = '';
          }}
        />
      </div>

      {uploads.length > 0 && (
        <ul data-testid="attachment-uploads" className="space-y-2">
          {uploads.map((u) => (
            <li key={u.key} className="rounded-md border border-border px-3 py-2 text-sm">
              <div className="flex items-center justify-between gap-2">
                <span className="truncate">{u.name}</span>
                {u.status === 'uploading' ? (
                  <Loader2 className="h-4 w-4 shrink-0 animate-spin text-muted-foreground" />
                ) : (
                  <button
                    type="button"
                    aria-label="Dismiss"
                    onClick={() => setUploads((prev) => prev.filter((x) => x.key !== u.key))}
                  >
                    <X className="h-4 w-4 shrink-0 text-muted-foreground" />
                  </button>
                )}
              </div>
              {u.status === 'uploading' ? (
                <div className="mt-1 h-1 w-full overflow-hidden rounded bg-muted">
                  <div className="h-full bg-primary" style={{ width: `${u.progress}%` }} />
                </div>
              ) : (
                <p className="mt-1 text-xs text-destructive">{u.error}</p>
              )}
            </li>
          ))}
        </ul>
      )}

      {query.isError && <p className="text-sm text-muted-foreground">Could not load attachments.</p>}
      {!query.isLoading &&
        !query.isError &&
        attachments.length === 0 &&
        uploads.length === 0 && (
          <p data-testid="attachments-empty" className="text-sm text-muted-foreground">
            No attachments yet.
          </p>
        )}

      {images.length > 0 && (
        <ul className="grid grid-cols-3 gap-2 sm:grid-cols-4">
          {images.map((a) => (
            <li key={a.id} className="group relative">
              <ThumbnailButton attachment={a} onOpen={() => openAttachment(a)} />
              {canDelete(a) && (
                <button
                  type="button"
                  aria-label={`Delete ${a.filename}`}
                  className="absolute right-1 top-1 rounded bg-background/80 p-1 opacity-0 group-hover:opacity-100"
                  onClick={() => deleteMut.mutate(a.id)}
                >
                  <Trash2 className="h-3.5 w-3.5 text-destructive" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {files.length > 0 && (
        <ul className="space-y-1">
          {files.map((a) => (
            <li
              key={a.id}
              data-testid="attachment-row"
              className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent"
            >
              <FileText className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <button
                type="button"
                className="flex-1 truncate text-left"
                onClick={() => openAttachment(a)}
              >
                {a.filename}
              </button>
              <span className="shrink-0 text-xs text-muted-foreground">
                {formatBytes(a.sizeBytes)}
              </span>
              {canDelete(a) && (
                <button
                  type="button"
                  aria-label={`Delete ${a.filename}`}
                  onClick={() => deleteMut.mutate(a.id)}
                >
                  <Trash2 className="h-4 w-4 text-destructive" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Lazily fetches a fresh presigned thumbnail URL (cached under the 5-min GET TTL). */
function ThumbnailButton({ attachment, onOpen }: { attachment: Attachment; onOpen: () => void }) {
  const thumb = useQuery({
    queryKey: [...attachmentKeys.list(attachment.ticketId), attachment.id, 'thumbnail'],
    queryFn: () => getAttachmentDownloadUrl(attachment.id, true),
    enabled: attachment.hasThumbnail,
    staleTime: 4 * 60 * 1000,
  });
  return (
    <button
      type="button"
      onClick={onOpen}
      className="block aspect-square w-full overflow-hidden rounded-md border border-border bg-muted"
      aria-label={`Open ${attachment.filename}`}
    >
      {thumb.data ? (
        <img
          src={thumb.data}
          alt={attachment.filename}
          loading="lazy"
          className="h-full w-full object-cover"
        />
      ) : (
        <span className="flex h-full w-full items-center justify-center text-xs text-muted-foreground">
          {attachment.filename}
        </span>
      )}
    </button>
  );
}
