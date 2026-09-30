import { resolvePublicHousingApiBaseUrl } from './publicHousingRepository.ts'

class AttachmentError extends Error {
  readonly code: string
  constructor(code: string) {
    super(code)
    this.code = code
  }
}

export function hasAttachmentUrl(attachment: { readonly fileUrl: string | null }): boolean {
  try {
    const url = new URL(attachment.fileUrl ?? '')
    return url.protocol === 'https:' || url.protocol === 'http:'
  } catch {
    return false
  }
}

export async function loadAnnouncementAttachment(
  announcementId: string,
  attachmentId: string,
  download: boolean,
  signal: AbortSignal,
): Promise<Blob> {
  if (![announcementId, attachmentId].every((id) => /^[1-9]\d*$/.test(id))) {
    throw new AttachmentError('ATTACHMENT_NOT_FOUND')
  }
  const response = await fetch(
    `${resolvePublicHousingApiBaseUrl()}/api/v1/announcements/${announcementId}/attachments/${attachmentId}/content?download=${download}`,
    { signal: AbortSignal.any([signal, AbortSignal.timeout(150_000)]) },
  )
  if (!response.ok) {
    const payload: unknown = await response.json().catch(() => null)
    const code = typeof payload === 'object' && payload !== null && 'code' in payload
      && typeof payload.code === 'string' ? payload.code : 'ATTACHMENT_UPSTREAM_FAILURE'
    throw new AttachmentError(code)
  }
  if (!download && response.headers.get('Content-Type')?.split(';')[0] !== 'application/pdf') {
    throw new AttachmentError('ATTACHMENT_NOT_PDF')
  }
  const blob = await response.blob()
  if (blob.size === 0) {
    throw new AttachmentError('ATTACHMENT_UPSTREAM_FAILURE')
  }
  return blob
}

export function attachmentErrorMessage(error: unknown): string {
  if (error instanceof AttachmentError) {
    switch (error.code) {
      case 'ATTACHMENT_NOT_PDF':
        return '이 파일은 PDF 미리보기를 지원하지 않습니다. 다운로드해서 확인해 주세요.'
      case 'ATTACHMENT_NOT_FOUND':
        return '첨부파일을 찾을 수 없습니다. 공고 원문에서 확인해 주세요.'
      case 'ATTACHMENT_UNSUPPORTED_SOURCE':
        return '이 첨부파일은 공고 원문에서 확인해 주세요.'
      case 'ATTACHMENT_TOO_LARGE':
        return '파일이 커서 여기서 열 수 없습니다. 공고 원문에서 확인해 주세요.'
      case 'ATTACHMENT_BUSY':
        return '파일 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    }
  }
  return '첨부파일을 불러오지 못했습니다. 다시 시도해 주세요.'
}
