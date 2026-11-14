import { expect, it } from 'vitest'
import { sampleSheet } from './fixtures/sheet'
import { changedReviewTargets } from './changedReviewTargets'
import { addCell, setLeaseAmount, setMembersCount, updateHousingType } from './sheetEdits'

it('적용 전후 공급표에서 바뀐 주택형과 칸만 표시 순서대로 고른다', () => {
  const before = sampleSheet()
  // 46A·59B가 함께 쓰는 임대조건은 46A 두 칸에 걸쳐 있어 두 칸 모두 바뀐다. 59B는 주택형 표기만 바뀐다.
  const after = updateHousingType(setLeaseAmount(before, 'cell:1', 'monthlyRent', '60000'), 'row:2', { name: '59B(신혼)' })
  expect(changedReviewTargets(before, after)).toEqual(['cell:1', 'cell:2', 'row:2'])
  expect(changedReviewTargets(before, setMembersCount(before, ['cell:3'], 'RESERVE', 2))).toEqual(['cell:3'])
})

it('새 칸은 칸 키로 고르고, 보호 표시만 바뀌었거나 바뀐 것이 없으면 빈 목록이다', () => {
  const before = sampleSheet()
  const added = addCell(before, 'row:2')
  expect(changedReviewTargets(before, added)).toEqual([added.cells.at(-1)!.key])
  expect(changedReviewTargets(before, updateHousingType(before, 'row:1', { adminModified: true }))).toEqual([])
  expect(changedReviewTargets(before, sampleSheet())).toEqual([])
})
