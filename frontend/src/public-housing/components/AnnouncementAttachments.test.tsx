import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AttachmentDialog, AttachmentList } from './AnnouncementAttachments.tsx'
import { loadAnnouncementAttachment } from '../api/announcementAttachments.ts'
import type { HousingAnnouncementDetailAttachment } from './HousingAnnouncementDetailPanel.tsx'

vi.mock('./PdfDocumentPreview.tsx', () => ({
  default: ({ name, url }: { name: string; url: string }) => <div title={`${name} 미리보기`} data-url={url} />,
}))

vi.mock('../api/announcementAttachments.ts', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/announcementAttachments.ts')>(),
  loadAnnouncementAttachment: vi.fn(),
  attachmentErrorMessage: () => '첨부파일을 불러오지 못했습니다. 다시 시도해 주세요.',
}))

const pdf: HousingAnnouncementDetailAttachment = {
  attachmentId: '1', fileName: '모집공고.pdf', fileTypeLabel: '공고문',
  fileUrl: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1',
}
const reference = { ...pdf, attachmentId: '2', fileName: '참고자료.pdf', fileTypeLabel: '참고자료' }

beforeEach(() => {
  vi.mocked(loadAnnouncementAttachment).mockResolvedValue(new Blob(['%PDF-1.7'], { type: 'application/pdf' }))
  // jsdom does not implement native dialog or object URLs; real behavior is checked in the browser.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() {} })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() {} })
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value() {} })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value() {} })
  vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
    this.open = true
    this.querySelector<HTMLButtonElement>('button')?.focus()
  })
  vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(function (this: HTMLDialogElement) {
    this.open = false
  })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => vi.restoreAllMocks())

describe('첨부파일', () => {
  it('하나면 곧바로 PDF를 불러오고 닫힐 때 요청과 URL을 정리하고 포커스를 돌려준다', async () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const { unmount } = render(<AttachmentDialog announcementId="201" attachments={[pdf]} onClose={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('PDF를 불러오는 중')
    expect(await screen.findByTitle('모집공고.pdf 미리보기')).toHaveAttribute('data-url', 'blob:preview')
    const signal = vi.mocked(loadAnnouncementAttachment).mock.calls[0]![3]
    unmount()
    expect(signal.aborted).toBe(true)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview')
    expect(trigger).toHaveFocus()
    trigger.remove()
  })

  it('여러 개면 자동으로 파일을 고르지 않고 선택한 파일만 연다', async () => {
    vi.mocked(loadAnnouncementAttachment).mockClear()
    render(<AttachmentDialog announcementId="201" attachments={[pdf, reference]} onClose={vi.fn()} />)
    expect(screen.getByText('확인할 파일을 선택해 주세요.')).toBeVisible()
    expect(loadAnnouncementAttachment).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /참고자료.pdf/ }))
    expect(await screen.findByTitle('참고자료.pdf 미리보기')).toBeVisible()
    expect(loadAnnouncementAttachment).toHaveBeenCalledWith('201', '2', false, expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: '파일 목록' }))
    expect(screen.getByRole('button', { name: /모집공고.pdf/ })).toBeVisible()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview')
  })

  it('비PDF는 자동 다운로드 없이 지원 안내와 다운로드 버튼을 제공한다', () => {
    vi.mocked(loadAnnouncementAttachment).mockClear()
    render(<AttachmentDialog announcementId="201" attachments={[{ ...pdf, fileName: '신청서.hwpx' }]} onClose={vi.fn()} />)
    expect(screen.getByText('이 파일은 미리보기를 지원하지 않습니다. 다운로드해서 확인해 주세요.')).toBeVisible()
    expect(screen.getByRole('button', { name: '신청서.hwpx 다운로드' })).toBeEnabled()
    expect(loadAnnouncementAttachment).not.toHaveBeenCalled()
  })

  it('미리보기 실패를 표시하고 다시 시도할 수 있다', async () => {
    vi.mocked(loadAnnouncementAttachment).mockRejectedValueOnce(new Error('offline'))
    render(<AttachmentDialog announcementId="201" attachments={[pdf]} onClose={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('첨부파일을 불러오지 못했습니다')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByTitle('모집공고.pdf 미리보기')).toBeVisible()
  })

  it('모달에서 Escape를 누르면 상세 패널에 전달하지 않고 모달만 닫는다', () => {
    const close = vi.fn()
    const parentKey = vi.fn()
    render(<div onKeyDown={parentKey}><AttachmentDialog announcementId="201" attachments={[pdf, reference]} onClose={close} /></div>)
    const dialog = screen.getByRole('dialog')
    fireEvent.keyDown(dialog, { key: 'Escape' })
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    expect(close).toHaveBeenCalledTimes(1)
    expect(parentKey).not.toHaveBeenCalled()
  })

  it('하단 목록은 하나여도 접혀 있고 펼치면 다운로드 동작을 제공한다', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<AttachmentList announcementId="201" attachments={[pdf]} />)
    const toggle = screen.getByRole('button', { name: '첨부파일 1개' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('button', { name: /모집공고.pdf 다운로드/ })).not.toBeInTheDocument()
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('button', { name: '모집공고.pdf 다운로드' }))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(loadAnnouncementAttachment).toHaveBeenCalledWith('201', '1', true, expect.any(AbortSignal))
    const anchor = click.mock.instances[0]!
    if (!(anchor instanceof HTMLAnchorElement)) throw new Error('download anchor missing')
    expect(anchor.download).toBe('모집공고.pdf')
    expect(anchor.href).toBe('blob:preview')
    fireEvent.click(toggle)
    expect(within(screen.getByRole('region', { name: '첨부파일 1개' })).queryByRole('button', { name: /다운로드/ })).not.toBeInTheDocument()
  })
})
