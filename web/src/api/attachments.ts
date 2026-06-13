import {
  type AttachmentResponse,
  AttachmentsService,
  type InitUploadResponse,
} from './generated';

/**
 * T-025 app-facing attachment API, mirroring tickets.ts / notifications.ts.
 *
 * The file bytes NEVER go through the generated client or its auth interceptor: init
 * returns a presigned PUT URL, the browser PUTs the bytes directly to S3 via raw XHR
 * (self-authorizing — no Authorization header), then finalize verifies server-side.
 */
export interface Attachment {
  id: string;
  ticketId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  hasThumbnail: boolean;
  createdAt: string;
}

export interface InitUpload {
  attachmentId: string;
  uploadUrl: string;
  headers: Record<string, string>;
}

/** Maps a generated AttachmentResponse to the app shape; throws on a malformed row. */
export function toAttachment(r: AttachmentResponse): Attachment {
  if (!r.id) {
    throw new Error('Malformed attachment response: missing id');
  }
  return {
    id: r.id,
    ticketId: r.ticketId ?? '',
    filename: r.filename ?? '',
    contentType: r.contentType ?? '',
    sizeBytes: r.sizeBytes ?? 0,
    uploadedBy: r.uploadedBy ?? '',
    hasThumbnail: r.hasThumbnail ?? false,
    createdAt: r.createdAt ?? '',
  };
}

/** TanStack Query keys — one list per ticket. */
export const attachmentKeys = {
  all: ['attachments'] as const,
  list: (ticketId: string) => [...attachmentKeys.all, ticketId] as const,
};

export function isImage(contentType: string): boolean {
  return contentType.toLowerCase().startsWith('image/');
}

export async function initAttachmentUpload(
  ticketId: string,
  filename: string,
  contentType: string,
  sizeBytes: number
): Promise<InitUpload> {
  const res: InitUploadResponse = await AttachmentsService.initAttachmentUpload(ticketId, {
    filename,
    contentType,
    sizeBytes,
  });
  return {
    attachmentId: res.attachmentId ?? '',
    uploadUrl: res.uploadUrl ?? '',
    headers: res.headers ?? {},
  };
}

export async function finalizeAttachment(id: string): Promise<void> {
  await AttachmentsService.finalizeAttachment(id);
}

export async function listAttachments(ticketId: string): Promise<Attachment[]> {
  return (await AttachmentsService.listTicketAttachments(ticketId)).map(toAttachment);
}

export async function getAttachmentDownloadUrl(id: string, thumbnail = false): Promise<string> {
  return (await AttachmentsService.getAttachmentDownloadUrl(id, thumbnail)).url ?? '';
}

export async function deleteAttachment(id: string): Promise<void> {
  await AttachmentsService.deleteAttachment(id);
}

/**
 * PUTs a file to a presigned URL with the EXACT signed Content-Type, reporting
 * progress. Raw XHR (not fetch) because fetch has no upload-progress event, and NOT
 * the generated client because the presigned URL is self-authorizing (no Bearer).
 */
export function uploadToPresignedUrl(
  url: string,
  file: File,
  contentType: string,
  onProgress?: (percent: number) => void
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    xhr.setRequestHeader('Content-Type', contentType);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new Error(`Upload failed (HTTP ${xhr.status})`));
      }
    };
    xhr.onerror = () => reject(new Error('Upload network error'));
    xhr.send(file);
  });
}

/** Human-readable size for the file list. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(1)} ${units[i]}`;
}
