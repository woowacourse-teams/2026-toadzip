import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AttachmentDialog } from './AnnouncementAttachments.tsx'
import { loadAnnouncementAttachment } from '../api/announcementAttachments.ts'

const sdk = vi.hoisted(() => ({ loaded: [] as string[], getDocument: vi.fn() }))
// Keep the real preview module; only the external SDK is replaced because
// jsdom cannot run its canvas/worker. Import order is part of the SDK contract.
vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => {
  sdk.loaded.push('core')
  return { AnnotationMode: { ENABLE: 1 }, GlobalWorkerOptions: {}, version: '6.3.289', getDocument: sdk.getDocument }
})
vi.mock('pdfjs-dist/legacy/web/pdf_viewer.mjs', () => {
  sdk.loaded.push('viewer')
  return {}
})
vi.mock('../api/announcementAttachments.ts', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/announcementAttachments.ts')>(),
  loadAnnouncementAttachment: vi.fn(),
}))

afterEach(() => vi.restoreAllMocks())

it('PDF 선택 시 파일 수신 전에 뷰어 코드를 순서대로 준비하되 문서 해석은 시작하지 않는다', async () => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false } })
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn() })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  let resolve!: (blob: Blob) => void
  const response = new Promise<Blob>((done) => { resolve = done })
  vi.mocked(loadAnnouncementAttachment).mockReturnValue(response)
  const pdf = { attachmentId: '1', fileName: '공고.pdf', fileTypeLabel: '공고문', fileUrl: 'https://apply.lh.or.kr/file' }
  const attachments = ['hwp', 'hwpx', 'zip'].map((extension, index) => ({
    ...pdf, attachmentId: String(index + 2), fileName: `공고.${extension}`,
  }))
  const { unmount } = render(<AttachmentDialog announcementId="118"
    attachments={[pdf, ...attachments, { ...pdf, attachmentId: '5', fileName: '없는.pdf', fileUrl: null }]} onClose={vi.fn()} />)
  expect(loadAnnouncementAttachment).not.toHaveBeenCalled()
  expect(sdk.loaded).toEqual([])
  expect(screen.getByRole('button', { name: /없는.pdf/ })).toBeDisabled()
  for (const attachment of attachments) {
    fireEvent.click(screen.getByRole('button', { name: (name) => name.endsWith(attachment.fileName) }))
    await act(async () => {})
    expect(sdk.loaded).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: '파일 목록' }))
  }

  fireEvent.click(screen.getByRole('button', { name: /공고.pdf/ }))
  expect(loadAnnouncementAttachment).toHaveBeenLastCalledWith('118', '1', false, expect.any(AbortSignal))
  await waitFor(() => expect(sdk.loaded).toEqual(['core', 'viewer']))
  expect(screen.getByRole('status')).toHaveTextContent('PDF를 불러오는 중')
  expect(sdk.getDocument).not.toHaveBeenCalled()
  expect(URL.createObjectURL).not.toHaveBeenCalled()

  const signal = vi.mocked(loadAnnouncementAttachment).mock.calls.at(-1)![3]
  unmount()
  expect(signal.aborted).toBe(true)
  await act(async () => { resolve(new Blob(['%PDF-1.7'], { type: 'application/pdf' })) })
  expect(sdk.getDocument).not.toHaveBeenCalled()
  expect(URL.createObjectURL).not.toHaveBeenCalled()
})
