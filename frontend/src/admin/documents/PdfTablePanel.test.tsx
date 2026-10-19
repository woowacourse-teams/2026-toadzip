import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { PdfTablePanel } from './PdfTablePanel'
import type { ReviewTarget } from './documentReview'
const target: ReviewTarget = { key: 'complexes:7:basic', label: '단지 기본정보', version: 1, endpoint: '/', current: {}, body: {},
  fields: [{ name: 'totalHouseholdCount', label: '전체 세대수', type: 'number', unit: '세대', scope: '단지 전체' }] }
const document = { fileName: 'test.pdf', sha256: 'a'.repeat(64), totalPages: 1, pages: [{ page: 1, text: '전체 120', requiresOcr: false,
  items: [{ str: '120', x: 100, y: 100, width: 20, height: 10, endOfLine: true }] }] }
it('셀·단위·적용 대상을 확인해야 수정안을 만들고 DB에는 자동 반영하지 않는다', () => {
  const added = vi.fn()
  render(<PdfTablePanel document={document} target={target} disabled={false} onAdded={added} />)
  fireEvent.click(screen.getByText('표의 행·셀로 수정안 만들기'))
  fireEvent.click(screen.getByRole('button', { name: '1쪽 1행 1셀 120' }))
  const button = screen.getByRole('button', { name: '표 셀 수정안 추가' })
  expect(button).toBeDisabled()
  fireEvent.change(screen.getByLabelText('표에 표시된 원문 단위'), { target: { value: '세대' } })
  fireEvent.change(screen.getByLabelText('표의 적용 단지·주택형·공급대상'), { target: { value: '단지 전체 행' } })
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(button)
  expect(added).toHaveBeenCalledWith(expect.objectContaining({ field: 'totalHouseholdCount', value: 120, quote: '120', scope: '단지 전체' }))
  expect(button).toBeDisabled()
})
it('OCR에 위치가 없으면 표를 추정하지 않고 직접 입력으로 안내한다', () => {
  render(<PdfTablePanel document={{ ...document, pages: [{ ...document.pages[0], method: 'ocr' }] }} target={target} disabled={false} onAdded={vi.fn()} />)
  fireEvent.click(screen.getByText('표의 행·셀로 수정안 만들기'))
  expect(screen.getByText(/위치를 확인할 수 있는 텍스트가 없습니다/)).toBeVisible()
})
