import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import { sampleSheet } from './fixtures/sheet'
import { SupplyCorrectionPreview } from './SupplyCorrectionPreview'
import { addCell, addSharedQuota, removeHousingType, setCellTarget, setLeaseAmount } from './sheetEdits'

const rows = () => within(screen.getByRole('table')).getAllByRole('row').slice(1).map(row => row.textContent)

it('새로 추가한 공급대상은 입력한 값만 보여 주고 빈 값과 대상 이름 중복은 뺀다', () => {
  const before = sampleSheet()
  const added = addCell(before, 'row:2'), cell = added.cells.at(-1)!.key
  const after = setLeaseAmount(setCellTarget(added, cell, '청년', ''), cell, 'rentalDeposit', '2603000')
  render(<SupplyCorrectionPreview historical value={{ confirmationToken: '', before, after, warnings: [] }} />)
  expect(rows()).toEqual(['59B 청년공급대상없음새 공급대상 추가', '59B 청년보증금 (원)없음2603000'])
})

it('여러 주택형의 칸이 함께 쓰는 수는 멤버 칸 이름으로 보여 준다', () => {
  const before = sampleSheet()
  const shared = addSharedQuota(before, 'WINNER', ['cell:1', 'cell:3'], 70)
  if (typeof shared === 'string') throw new Error(shared)
  render(<SupplyCorrectionPreview value={{ confirmationToken: '', before, after: shared, warnings: [] }} />)
  expect(rows()).toEqual(['46A 일반·59B 일반함께 쓰는 모집 수없음70'])
})

it('지운 주택형은 공급대상 수와 함께 삭제로 보여 주고, 함께 쓰던 수가 남은 칸의 수로 바뀌는 것도 보인다', () => {
  const before = sampleSheet()
  render(<SupplyCorrectionPreview value={{ confirmationToken: '', before, after: removeHousingType(before, 'row:2'), warnings: [] }} />)
  expect(rows()).toEqual(['두꺼비 · 59B주택형공급대상 1개삭제', '46A 고령자예비 수미확인8', '46A 고령자·59B 일반함께 쓰는 예비 수8삭제'])
})
