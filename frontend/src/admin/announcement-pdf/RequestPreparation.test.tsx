import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { RequestPreparation } from './RequestPreparation'
import prompt from './announcementUpdatePrompt.v3.txt?raw'

const attachments = [{ attachmentId: 1, fileName: '공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/1.pdf' }]
function props() {
  return { attachments, attachmentId: '1', onAttachment: vi.fn(), pdf: { url: 'blob:notice', name: '공고문.pdf' }, pdfError: '', text: '', onText: vi.fn(), parsed: { ok: false as const, error: '', repairs: [] }, checks: [], onReview: vi.fn() }
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('한 번의 준비로 전체 프롬프트를 복사하고 ChatGPT 탭과 PDF 다운로드를 연다', async () => {
  let copied!: () => void
  const writeText = vi.fn(() => new Promise<void>(resolve => { copied = resolve }))
  vi.stubGlobal('navigator', Object.create(navigator, { clipboard: { value: { writeText } } }))
  const open = vi.spyOn(window, 'open').mockReturnValue(null)
  const downloaded: string[][] = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { downloaded.push([this.href, this.download]) })
  render(<RequestPreparation {...props()} />)
  fireEvent.click(screen.getByRole('button', { name: 'PDF 받고 프롬프트 복사해 ChatGPT 열기' }))
  expect(writeText).toHaveBeenCalledExactlyOnceWith(prompt)
  expect(open).toHaveBeenCalledExactlyOnceWith('https://chatgpt.com/', '_blank', 'noopener,noreferrer')
  expect(downloaded).toEqual([])
  await act(async () => copied())
  expect(downloaded).toEqual([['blob:notice', '공고문.pdf']])
  expect(screen.getByRole('status')).toHaveTextContent('PDF 다운로드를 시작하고 프롬프트를 복사했습니다.')
})
it('복사가 거부되면 성공했다고 표시하지 않고 직접 복사할 내용을 유지한다', async () => {
  vi.stubGlobal('navigator', Object.create(navigator, { clipboard: { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } } }))
  vi.spyOn(window, 'open').mockReturnValue(null)
  const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  render(<RequestPreparation {...props()} />)
  fireEvent.click(screen.getByRole('button', { name: 'PDF 받고 프롬프트 복사해 ChatGPT 열기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('프롬프트를 복사하지 못했습니다.')
  expect(screen.getByLabelText('프롬프트 내용')).toHaveTextContent('첨부한 공식 공고문 PDF에서')
  expect(screen.getByRole('link', { name: 'PDF 다운로드' })).toHaveAttribute('href', 'blob:notice')
  expect(download).not.toHaveBeenCalled()
})
it('공고문 드롭다운에서 사용할 PDF를 바꾼다', () => {
  const input = props()
  render(<RequestPreparation {...input} attachments={[...attachments, { ...attachments[0], attachmentId: 2, fileName: '추가 공고문.pdf' }]} />)
  fireEvent.click(screen.getByRole('combobox', { name: '공고문 PDF' }))
  fireEvent.click(screen.getByRole('option', { name: '추가 공고문.pdf' }))
  expect(input.onAttachment).toHaveBeenCalledWith('2')
})

it('파일 목록은 키보드로 선택하고 Escape나 바깥 클릭으로 닫는다', () => {
  const input = props()
  render(<RequestPreparation {...input} attachments={[...attachments, { ...attachments[0], attachmentId: 2, fileName: '추가 공고문.pdf' }]} />)
  const picker = screen.getByRole('combobox', { name: '공고문 PDF' })
  picker.focus()
  fireEvent.keyDown(picker, { key: 'ArrowDown' })
  expect(screen.getByRole('listbox')).toBeVisible()
  fireEvent.keyDown(picker, { key: 'ArrowDown' })
  fireEvent.keyDown(picker, { key: 'Enter' })
  expect(input.onAttachment).toHaveBeenCalledWith('2')
  expect(picker).toHaveFocus()
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  fireEvent.click(picker)
  fireEvent.keyDown(picker, { key: 'Escape' })
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  fireEvent.click(picker)
  fireEvent.mouseDown(screen.getByRole('heading', { name: '응답 가져오기' }))
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
})
