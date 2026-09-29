import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AttachmentDialog } from './AnnouncementAttachments.tsx'
import { loadAnnouncementAttachment } from '../api/announcementAttachments.ts'

const sdk = vi.hoisted(() => ({ attempted: vi.fn() }))
vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => ({
  AnnotationMode: { ENABLE: 1 }, GlobalWorkerOptions: {}, version: '6.3.289', getDocument: vi.fn(),
}))
vi.mock('pdfjs-dist/legacy/web/pdf_viewer.mjs', () => {
  sdk.attempted()
  throw new Error('viewer chunk unavailable')
})
vi.mock('../api/announcementAttachments.ts', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/announcementAttachments.ts')>(),
  loadAnnouncementAttachment: vi.fn(),
}))
afterEach(() => vi.restoreAllMocks())

it('web viewer 사전 로딩 실패를 lazy 캐시에 전파하지 않고 기존 문서 오류와 원본 다운로드를 유지한다', async () => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() {} })
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn().mockReturnValue('blob:original') })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const original = new Blob(['%PDF-1.7 original bytes'], { type: 'application/pdf' })
  let resolve!: (blob: Blob) => void
  vi.mocked(loadAnnouncementAttachment).mockReturnValue(new Promise((done) => { resolve = done }))
  render(<AttachmentDialog announcementId="118" onClose={vi.fn()} attachments={[{
    attachmentId: '1', fileName: '원본.pdf', fileTypeLabel: '공고문', fileUrl: 'https://apply.lh.or.kr/file',
  }]} />)
  await waitFor(() => expect(sdk.attempted).toHaveBeenCalled())
  expect(screen.getByRole('status')).toHaveTextContent('PDF를 불러오는 중')
  await act(async () => { resolve(original) })
  expect(await screen.findByRole('alert')).toHaveTextContent('PDF를 표시하지 못했습니다')
  // The real preview still mounted; only its per-document SDK operation failed.
  expect(screen.getByRole('region', { name: '원본.pdf 문서' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '원본.pdf 다운로드' }))
  expect(click).toHaveBeenCalledOnce()
  expect(loadAnnouncementAttachment).toHaveBeenCalledOnce()
  expect(URL.createObjectURL).toHaveBeenLastCalledWith(original)
  expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe('원본.pdf')
})
