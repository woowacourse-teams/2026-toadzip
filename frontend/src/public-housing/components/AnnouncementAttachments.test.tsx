import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AttachmentDialog, AttachmentList } from './AnnouncementAttachments.tsx'
import { loadAnnouncementAttachment } from '../api/announcementAttachments.ts'
import type { HousingAnnouncementDetailAttachment } from './HousingAnnouncementDetailPanel.tsx'

vi.mock('./PdfDocumentPreview.tsx', () => ({
  default: ({ name, url }: { name: string; url: string }) => {
    if (name === '깨진.pdf') throw new Error('Viewer failed')
    return <div title={`${name} 미리보기`} data-url={url} />
  },
}))
// Its core is replaced above, so the dependent web viewer is also an SDK boundary double.
vi.mock('pdfjs-dist/legacy/web/pdf_viewer.mjs', () => ({}))

vi.mock('./HwpDocumentPreview.tsx', () => ({
  default: ({ name }: { name: string }) => <div title={`${name} 한글 미리보기`} />,
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
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

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

  it('ZIP은 자동 다운로드 없이 지원 안내와 다운로드 버튼을 제공한다', () => {
    vi.mocked(loadAnnouncementAttachment).mockClear()
    render(<AttachmentDialog announcementId="201" attachments={[{ ...pdf, fileName: '신청서.zip' }]} onClose={vi.fn()} />)
    expect(screen.getByText('이 파일은 미리보기를 지원하지 않습니다. 다운로드해서 확인해 주세요.')).toBeVisible()
    expect(screen.getByRole('button', { name: '신청서.zip 다운로드' })).toBeEnabled()
    expect(loadAnnouncementAttachment).not.toHaveBeenCalled()
  })

  it.each(['hwp', 'hwpx'])('%s는 원본 바이트를 요청해 한글 뷰어로 연다', async (extension) => {
    render(<AttachmentDialog announcementId="201" attachments={[{ ...pdf, fileName: `공고문.${extension}` }]} onClose={vi.fn()} />)
    expect(await screen.findByTitle(`공고문.${extension} 한글 미리보기`)).toBeVisible()
    expect(loadAnnouncementAttachment).toHaveBeenCalledWith('201', '1', true, expect.any(AbortSignal))
    expect(screen.queryByRole('link', { name: 'PDF 새 창에서 보기' })).not.toBeInTheDocument()
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


function deferredBlob() {
  let resolve!: (blob: Blob) => void
  const promise = new Promise<Blob>((done) => { resolve = done })
  return { promise, resolve }
}

async function bytesOf(blob: Blob): Promise<number[]> {
  const bytes = await new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(blob)
  })
  return Array.from(new Uint8Array(bytes))
}

describe('미리보기 원본 다운로드 재사용', () => {
  it.each(['pdf', 'hwp', 'hwpx'])('%s 원본 바이트와 파일명으로 저장하고 API를 다시 호출하지 않는다', async (extension) => {
    const original = new Blob([new Uint8Array([0, 255, 37, 80, 68, 70, 128])], { type: 'application/octet-stream' })
    vi.mocked(loadAnnouncementAttachment).mockReset().mockResolvedValue(original)
    vi.mocked(URL.createObjectURL).mockReturnValueOnce('blob:preview').mockReturnValueOnce('blob:download')
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const name = `★공고문(최종).${extension}`
    render(<AttachmentDialog announcementId="201" attachments={[{ ...pdf, fileName: name }]} onClose={vi.fn()} />)
    await screen.findByTitle(`${name}${extension === 'pdf' ? ' 미리보기' : ' 한글 미리보기'}`)
    fireEvent.click(screen.getByRole('button', { name: `${name} 다운로드` }))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(loadAnnouncementAttachment).toHaveBeenCalledTimes(1)
    expect(vi.mocked(URL.createObjectURL).mock.calls[1]![0]).toBe(original)
    expect(await bytesOf(vi.mocked(URL.createObjectURL).mock.calls[1]![0] as Blob))
      .toEqual([0, 255, 37, 80, 68, 70, 128])
    const anchor = click.mock.instances[0] as HTMLAnchorElement
    expect(anchor.download).toBe(name)
    expect(anchor.href).toBe('blob:download')
  })

  it('뷰어 렌더링에 실패해도 이미 받은 원본으로 다운로드한다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(loadAnnouncementAttachment).mockClear()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<AttachmentDialog announcementId="201" attachments={[{ ...pdf, fileName: '깨진.pdf' }]} onClose={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('뷰어를 불러오지 못했습니다')
    fireEvent.click(screen.getByRole('button', { name: '깨진.pdf 다운로드' }))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(loadAnnouncementAttachment).toHaveBeenCalledTimes(1)
  })

  it('미리보기 원본을 받지 못했으면 기존 다운로드 API를 호출한다', async () => {
    vi.mocked(loadAnnouncementAttachment).mockReset().mockRejectedValueOnce(new Error('preview unavailable'))
      .mockResolvedValueOnce(new Blob(['original']))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<AttachmentDialog announcementId="201" attachments={[pdf]} onClose={vi.fn()} />)
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: '모집공고.pdf 다운로드' }))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(loadAnnouncementAttachment).toHaveBeenNthCalledWith(2, '201', '1', true, expect.any(AbortSignal))
  })

  it('닫을 때 미리보기 URL만 즉시 정리하고 다운로드 URL은 브라우저에 전달한 뒤 정리한다', async () => {
    vi.mocked(URL.createObjectURL).mockReturnValueOnce('blob:preview').mockReturnValueOnce('blob:download')
    const { unmount } = render(<AttachmentDialog announcementId="201" attachments={[pdf]} onClose={vi.fn()} />)
    await screen.findByTitle('모집공고.pdf 미리보기')
    vi.useFakeTimers()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => unmount())
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '모집공고.pdf 다운로드' })) })
    expect(click).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview')
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:download')
    act(() => vi.runOnlyPendingTimers())
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:download')
  })

  it.each(['닫기', '파일 전환'])('원본이 없어 다운로드 API로 받는 중 %s해도 완료한다', async (action) => {
    const preview = deferredBlob()
    const download = deferredBlob()
    const original = new Blob([new Uint8Array([255, 0, 123])])
    vi.mocked(loadAnnouncementAttachment).mockReset().mockImplementation((_announcement, _attachment, isDownload) =>
      isDownload ? download.promise : preview.promise)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const { unmount } = render(<AttachmentDialog announcementId="201" attachments={[pdf, reference]} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /모집공고.pdf/ }))
    fireEvent.click(screen.getByRole('button', { name: '모집공고.pdf 다운로드' }))
    const previewSignal = vi.mocked(loadAnnouncementAttachment).mock.calls[0]![3]
    const downloadSignal = vi.mocked(loadAnnouncementAttachment).mock.calls[1]![3]
    if (action === '닫기') unmount()
    else fireEvent.click(screen.getByRole('button', { name: '파일 목록' }))
    expect(previewSignal.aborted).toBe(true)
    expect(downloadSignal.aborted).toBe(false)
    await act(async () => { download.resolve(original) })
    expect(click).toHaveBeenCalledTimes(1)
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe('모집공고.pdf')
    expect(URL.createObjectURL).toHaveBeenCalledWith(original)
    await act(async () => { preview.resolve(new Blob(['late preview'])) })
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('파일을 바꾸면 이전 Blob을 재사용하지 않고 새 파일의 원본만 저장한다', async () => {
    const first = new Blob(['first'])
    const second = new Blob(['second'])
    vi.mocked(loadAnnouncementAttachment).mockReset().mockResolvedValueOnce(first).mockResolvedValueOnce(second)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<AttachmentDialog announcementId="201" attachments={[pdf, reference]} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /모집공고.pdf/ }))
    await screen.findByTitle('모집공고.pdf 미리보기')
    fireEvent.click(screen.getByRole('button', { name: '파일 목록' }))
    fireEvent.click(screen.getByRole('button', { name: /참고자료.pdf/ }))
    await screen.findByTitle('참고자료.pdf 미리보기')
    fireEvent.click(screen.getByRole('button', { name: '참고자료.pdf 다운로드' }))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect(loadAnnouncementAttachment).toHaveBeenCalledTimes(2)
    expect(vi.mocked(URL.createObjectURL).mock.calls[2]![0]).toBe(second)
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe('참고자료.pdf')
  })
})
