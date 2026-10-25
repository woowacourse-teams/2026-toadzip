import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PdfSource, PreparationFooter, RequestPreparation } from './RequestPreparation'
import { readExtraction } from './extraction'
import type { ExtractionState } from './extractionApi'
import fixtures from './fixtures/scenarios.json'

const attachments = [{ attachmentId: 1, fileName: '공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/1.pdf' }]
const json = JSON.stringify(fixtures.hansville.extraction)
const done: ExtractionState = { status: 'done', result: { json, pages: [1, 2, 10], totalPages: 35, warnings: ['supplyRows[0].targets[0].target: 표기 규칙 위반(「가」군)'], usage: { promptTokens: 7291, outputTokens: 1766, thinkingTokens: null } } }
function props(extraction: ExtractionState = done) {
  return { announcementName: '익산한스빌 예비입주자 모집', pdf: { url: 'blob:notice', name: '공고문.pdf' }, extraction, onExtract: vi.fn(),
    parsed: readExtraction(extraction.status === 'done' ? json : ''), checks: [] }
}
function sourceProps() {
  return { attachments, attachmentId: '1', onAttachment: vi.fn(), localPdf: false, onLocalFile: vi.fn(), pdf: { url: 'blob:notice', name: '공고문.pdf' }, pdfError: '' }
}
afterEach(() => { vi.useRealTimers() })

it('공고 제목을 보여주고 버튼을 눌러야 추출을 시작한다', () => {
  const input = props({ status: 'idle' })
  render(<RequestPreparation {...input} />)
  expect(screen.getByRole('region', { name: '1. 제목 확인' })).toHaveTextContent('익산한스빌 예비입주자 모집')
  expect(input.onExtract).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'PDF에서 값 추출' }))
  expect(input.onExtract).toHaveBeenCalledOnce()
})

it('PDF가 없으면 추출 버튼을 보여주지 않는다', () => {
  render(<RequestPreparation {...props({ status: 'idle' })} pdf={null} />)
  expect(screen.queryByRole('button', { name: 'PDF에서 값 추출' })).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('PDF를 고르면 추출할 수 있습니다.')
})

it('추출하는 동안 로딩바와 경과 시간을 보여준다', () => {
  vi.useFakeTimers({ now: 10_000 })
  render(<RequestPreparation {...props({ status: 'running', startedAt: 10_000 })} />)
  expect(screen.getByRole('status')).toHaveTextContent('PDF에서 값을 추출하는 중… 0초')
  expect(screen.getByRole('progressbar', { name: '추출 진행' })).toBeVisible()
  act(() => { vi.advanceTimersByTime(12_000) })
  expect(screen.getByRole('status')).toHaveTextContent('12초')
})

it('추출이 끝나면 추출 수·읽은 쪽·토큰·확인할 점을 보여준다', () => {
  render(<RequestPreparation {...props()} />)
  expect(screen.getByText(/공급행 2개 · 임대조건 2개 · 일정 3개/)).toBeVisible()
  expect(screen.getByText(/전체 35쪽 중 1, 2, 10쪽을 읽었습니다\. 토큰 입력 7,291 · 출력 1,766/)).toBeVisible()
  expect(screen.getByRole('region', { name: '확인할 점 1개' })).toHaveTextContent('표기 규칙 위반(「가」군)')
})

it('추출에 실패하면 이유를 알리고 다시 추출할 수 있다', () => {
  const input = props({ status: 'failed', message: 'AI 모델 호출에 실패했습니다.' })
  render(<RequestPreparation {...input} />)
  expect(screen.getByRole('alert')).toHaveTextContent('AI 모델 호출에 실패했습니다.')
  fireEvent.click(screen.getByRole('button', { name: '다시 추출' }))
  expect(input.onExtract).toHaveBeenCalledOnce()
})

it('하단 검토 버튼은 추출을 마쳐야 누를 수 있다', () => {
  const onReview = vi.fn()
  const { rerender } = render(<PreparationFooter ready={false} onReview={onReview} />)
  expect(screen.getByRole('button', { name: '추출 결과 검토하기' })).toBeDisabled()
  rerender(<PreparationFooter ready onReview={onReview} />)
  fireEvent.click(screen.getByRole('button', { name: '추출 결과 검토하기' }))
  expect(onReview).toHaveBeenCalledOnce()
})

it('등록된 PDF가 있어도 내 PC의 PDF를 고를 수 있다', () => {
  const input = sourceProps()
  render(<PdfSource {...input} />)
  const file = new File(['%PDF'], '공고문 원본.pdf', { type: 'application/pdf' })
  fireEvent.change(screen.getByLabelText('내 PC에서 PDF 고르기'), { target: { files: [file] } })
  expect(input.onLocalFile).toHaveBeenCalledExactlyOnceWith(file)
})

it('공고문 드롭다운에서 사용할 PDF를 바꾼다', () => {
  const input = sourceProps()
  render(<PdfSource {...input} attachments={[...attachments, { ...attachments[0], attachmentId: 2, fileName: '추가 공고문.pdf' }]} />)
  fireEvent.click(screen.getByRole('combobox', { name: '공고문 PDF' }))
  fireEvent.click(screen.getByRole('option', { name: '추가 공고문.pdf' }))
  expect(input.onAttachment).toHaveBeenCalledWith('2')
})

it('파일 목록은 키보드로 선택하고 Escape나 바깥 클릭으로 닫는다', () => {
  const input = sourceProps()
  render(<><p>바깥</p><PdfSource {...input} attachments={[...attachments, { ...attachments[0], attachmentId: 2, fileName: '추가 공고문.pdf' }]} /></>)
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
  fireEvent.mouseDown(screen.getByText('바깥'))
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
})
