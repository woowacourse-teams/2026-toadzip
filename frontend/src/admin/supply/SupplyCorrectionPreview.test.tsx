import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import { SupplyCorrectionPreview } from './SupplyCorrectionPreview'
import { blankRow, blankTarget } from './supplyCorrectionApi'

it('이력에서 새로 추가한 조건은 입력한 값만 보여 주고 기본값과 대상 이름 중복은 뺀다', () => {
  const before = { ...blankRow(0), id: 132, key: 'row:132', complexName: '평화주공1단지', typeName: '26' }
  const target = { ...blankTarget(0), key: 'new', target: '가군', deposit: '2603000', rent: '51760' }
  const after = { ...before, targets: [target] }
  render(<SupplyCorrectionPreview historical value={{ confirmationToken: '', before: [before], after: [after], warnings: [] }} />)
  const table = screen.getByRole('table')
  const rows = within(table).getAllByRole('row').slice(1).map(row => row.textContent)
  expect(rows).toEqual([
    '평화주공1단지 · 26 / 가군공급대상없음새 대상 추가',
    '평화주공1단지 · 26 / 가군보증금 (원)없음2603000',
    '평화주공1단지 · 26 / 가군월 임대료 (원)없음51760',
  ])
})
