import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Attachment } from '@/api/attachments';
import { renderWithProviders } from '@/test/test-utils';
import { AttachmentsSection } from '../AttachmentsSection';

// Mock the network functions; keep the pure helpers (isImage/formatBytes/attachmentKeys) real.
vi.mock('@/api/attachments', async (importActual) => {
  const actual = await importActual<typeof import('@/api/attachments')>();
  return {
    ...actual,
    listAttachments: vi.fn(),
    initAttachmentUpload: vi.fn(),
    uploadToPresignedUrl: vi.fn(),
    finalizeAttachment: vi.fn(),
    getAttachmentDownloadUrl: vi.fn(),
    deleteAttachment: vi.fn(),
  };
});

import {
  finalizeAttachment,
  getAttachmentDownloadUrl,
  initAttachmentUpload,
  listAttachments,
  uploadToPresignedUrl,
} from '@/api/attachments';

const TICKET = 't1';

function attachment(over: Partial<Attachment> = {}): Attachment {
  return {
    id: 'a1',
    ticketId: TICKET,
    filename: 'doc.pdf',
    contentType: 'application/pdf',
    sizeBytes: 2048,
    uploadedBy: 'u1',
    hasThumbnail: false,
    createdAt: '2026-06-12T10:00:00.000Z',
    ...over,
  };
}

afterEach(() => vi.clearAllMocks());

describe('AttachmentsSection', () => {
  it('renders the dropzone and an empty state', async () => {
    vi.mocked(listAttachments).mockResolvedValue([]);
    renderWithProviders(
      <AttachmentsSection ticketId={TICKET} currentUserId="u1" isProjectAdmin={false} />
    );
    expect(screen.getByTestId('attachment-dropzone')).toBeInTheDocument();
    expect(await screen.findByTestId('attachments-empty')).toBeInTheDocument();
  });

  it('renders an image thumbnail grid and a non-image file row', async () => {
    vi.mocked(listAttachments).mockResolvedValue([
      attachment({ id: 'img1', filename: 'shot.png', contentType: 'image/png', hasThumbnail: true }),
      attachment({ id: 'pdf1', filename: 'spec.pdf', contentType: 'application/pdf' }),
    ]);
    vi.mocked(getAttachmentDownloadUrl).mockResolvedValue('http://minio/thumb.jpg');

    renderWithProviders(
      <AttachmentsSection ticketId={TICKET} currentUserId="u1" isProjectAdmin={false} />
    );

    // The non-image renders as a row...
    expect(await screen.findByText('spec.pdf')).toBeInTheDocument();
    // ...and the image thumbnail fetches a presigned thumbnail URL.
    await waitFor(() =>
      expect(getAttachmentDownloadUrl).toHaveBeenCalledWith('img1', true)
    );
    await waitFor(() => {
      const img = document.querySelector('img');
      expect(img).not.toBeNull();
      expect(img?.getAttribute('src')).toBe('http://minio/thumb.jpg');
    });
  });

  it('opens a presigned URL in a new tab on row click', async () => {
    vi.mocked(listAttachments).mockResolvedValue([attachment({ id: 'pdf1', filename: 'spec.pdf' })]);
    vi.mocked(getAttachmentDownloadUrl).mockResolvedValue('http://minio/spec.pdf?sig');
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);

    renderWithProviders(
      <AttachmentsSection ticketId={TICKET} currentUserId="u1" isProjectAdmin={false} />
    );

    fireEvent.click(await screen.findByText('spec.pdf'));

    await waitFor(() => expect(getAttachmentDownloadUrl).toHaveBeenCalledWith('pdf1', false));
    await waitFor(() =>
      expect(openSpy).toHaveBeenCalledWith('http://minio/spec.pdf?sig', '_blank', 'noopener')
    );
  });

  it('runs init → upload → finalize when a file is selected', async () => {
    vi.mocked(listAttachments).mockResolvedValue([]);
    vi.mocked(initAttachmentUpload).mockResolvedValue({
      attachmentId: 'a9',
      uploadUrl: 'http://minio/put',
      headers: { 'Content-Type': 'application/pdf' },
    });
    vi.mocked(uploadToPresignedUrl).mockResolvedValue(undefined);
    vi.mocked(finalizeAttachment).mockResolvedValue({ status: 'READY' });

    renderWithProviders(
      <AttachmentsSection ticketId={TICKET} currentUserId="u1" isProjectAdmin={false} />
    );

    const input = screen.getByTestId('attachment-input') as HTMLInputElement;
    const file = new File(['hello'], 'note.pdf', { type: 'application/pdf' });
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() =>
      expect(initAttachmentUpload).toHaveBeenCalledWith(TICKET, 'note.pdf', 'application/pdf', 5)
    );
    await waitFor(() => expect(finalizeAttachment).toHaveBeenCalledWith('a9'));
  });

  it('surfaces a FAILED finalize (mismatch) as an inline error', async () => {
    vi.mocked(listAttachments).mockResolvedValue([]);
    vi.mocked(initAttachmentUpload).mockResolvedValue({
      attachmentId: 'a9',
      uploadUrl: 'http://minio/put',
      headers: {},
    });
    vi.mocked(uploadToPresignedUrl).mockResolvedValue(undefined);
    vi.mocked(finalizeAttachment).mockResolvedValue({ status: 'FAILED', reason: 'size_mismatch' });

    renderWithProviders(
      <AttachmentsSection ticketId={TICKET} currentUserId="u1" isProjectAdmin={false} />
    );

    const input = screen.getByTestId('attachment-input') as HTMLInputElement;
    fireEvent.change(input, {
      target: { files: [new File(['x'], 'note.pdf', { type: 'application/pdf' })] },
    });

    expect(await screen.findByText('Uploaded file size did not match')).toBeInTheDocument();
  });

  it('shows an inline error when the upload fails', async () => {
    vi.mocked(listAttachments).mockResolvedValue([]);
    vi.mocked(initAttachmentUpload).mockRejectedValue(new Error('nope'));

    renderWithProviders(
      <AttachmentsSection ticketId={TICKET} currentUserId="u1" isProjectAdmin={false} />
    );

    const input = screen.getByTestId('attachment-input') as HTMLInputElement;
    const file = new File(['x'], 'bad.bin', { type: 'application/octet-stream' });
    fireEvent.change(input, { target: { files: [file] } });

    expect(await screen.findByText('Upload failed')).toBeInTheDocument();
  });
});
