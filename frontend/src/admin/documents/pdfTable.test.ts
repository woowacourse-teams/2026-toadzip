import { expect, it } from 'vitest'
import { tableRows, tableProposal } from './pdfTable'
import type { ExtractedPage, PdfTextItem } from './pdfExtraction'
import type { ReviewField } from './documentReview'
const item = (str: string, x: number, y: number, width = 40): PdfTextItem => ({ str, x, y, width, height: 10, endOfLine: false })
const page: ExtractedPage = { page: 2, text: '36A 보증금 12,345 월 임대료 20', requiresOcr: false,
  items: [item('20', 420, 80), item('12,345', 240, 80), item('36A', 0, 80), item('보증금', 160, 100)] }
const field: ReviewField = { name: 'rentalDeposit', label: '보증금', money: true, unit: '원', scope: '해당 공급대상' }
it('텍스트 순서와 관계없이 위에서 아래·왼쪽에서 오른쪽으로 행과 셀 후보를 구성한다', () => {
  const rows = tableRows(page)
  expect(rows.map(row => row.cells.map(cell => cell.text))).toEqual([['보증금'], ['36A', '12,345', '20']])
  expect(tableRows({ ...page, method: 'ocr' })).toEqual([])
})
it('선택 셀의 천원 금액을 정밀도 손실 없이 원 문자열 수정안으로 만든다', () => {
  expect(tableProposal(page, '12,345', field, '천원', '36A 일반공급을 확인')).toMatchObject({
    field: 'rentalDeposit', value: '12345000', quote: '12,345', page: 2, unit: '원', scope: '해당 공급대상',
  })
  expect(tableProposal({ ...page, text: '900,719,925,474,099' }, '900,719,925,474,099', field, '원', '대상 확인').value).toBe('900719925474099')
})
it('범위·혼합 단위·여러 값·금액 상한 초과는 추정하지 않는다', () => {
  for (const value of ['12~15', '10/20', '-10', '1,00', '1억원', '999999999999999999999', '10원']) {
    expect(() => tableProposal({ ...page, text: value }, value, field, '천원', '확인')).toThrow()
  }
})
it('소수 금액은 원으로 정확히 환산할 수 있을 때만 허용하고 인용과 적용 대상을 요구한다', () => {
  expect(tableProposal({ ...page, text: '12.5' }, '12.5', field, '만원', '대상 확인').value).toBe('125000')
  expect(() => tableProposal(page, '12,345', field, '원', '')).toThrow()
  expect(() => tableProposal(page, '999', field, '원', '확인')).toThrow()
  expect(() => tableProposal({ ...page, text: '0.1' }, '0.1', field, '원', '확인')).toThrow()
})
