import { expect, it } from 'vitest'
import { blankRow, blankTarget, type CorrectionRow } from './supplyCorrectionApi'
import { changedReviewTargets } from './changedReviewTargets'

const target = { ...blankTarget(0), id: 2, key: 'target:2', target: '일반' }
const row: CorrectionRow = { ...blankRow(0), id: 1, key: 'row:1', typeName: '26', targets: [target] }
const other: CorrectionRow = { ...blankRow(1), id: 3, key: 'row:3', typeName: '36', targets: [] }

it('적용 전후 작업실에서 바뀐 행과 조건만 표시 순서대로 고른다', () => {
  const after = [{ ...row, targets: [{ ...target, rent: '200000' }] }, { ...other, typeName: '36A' }]
  expect(changedReviewTargets([row, other], after)).toEqual(['target:2', 'row:3'])
})

it('새로 생긴 행은 행과 하위 조건을 모두 고르고, 바뀌지 않으면 빈 목록이다', () => {
  const added: CorrectionRow = { ...blankRow(2), id: 5, key: 'row:5', targets: [{ ...blankTarget(0), id: 6, key: 'target:6' }] }
  expect(changedReviewTargets([row], [row, added])).toEqual(['row:5', 'target:6'])
  expect(changedReviewTargets([row, other], [row, other])).toEqual([])
})
