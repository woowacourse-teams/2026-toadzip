import { expect, it } from 'vitest'
import { blankRow, blankTarget } from './supplyCorrectionApi'
import { rebaseCorrection, resolveConflict } from './rebaseCorrection'

it('다른 필드 변경은 병합하고 겹치는 대상 조건은 명시적으로 선택한다', () => {
  const target = { ...blankTarget(0), id: 2, key: 'target:2', rent: '100' }
  const base = { ...blankRow(0), id: 1, key: 'row:1', typeName: '26', targets: [target] }
  const mine = { ...base, typeName: '26A', targets: [{ ...target, rent: '200' }] }
  const latest = { ...base, householdCount: 20, targets: [{ ...target, rent: '300', condition: '최신 조건' }] }
  const result = rebaseCorrection([base], [mine], [latest])
  expect(result.rows[0]).toMatchObject({ typeName: '26A', householdCount: 20 })
  expect(result.conflicts).toHaveLength(1)
  expect(result.rows[0].targets[0]).toMatchObject({ rent: '300', condition: '최신 조건' })
  expect(resolveConflict(result.rows, result.conflicts[0], true)[0].targets[0]).toMatchObject({ rent: '200', condition: '최신 조건' })
})

it('수정 중인 행이나 조건이 사라지면 입력을 조용히 버리지 않는다', () => {
  const target = { ...blankTarget(0), id: 2, key: 'target:2', rent: '100' }
  const base = { ...blankRow(0), id: 1, key: 'row:1', typeName: '26', targets: [target] }
  const mine = { ...base, targets: [{ ...target, rent: '200' }] }
  const missingRow = rebaseCorrection([base], [mine], [])
  expect(missingRow.conflicts[0].field).toBe('__missing')
  expect(missingRow.rows[0].targets[0].rent).toBe('200')
  expect(resolveConflict(missingRow.rows, missingRow.conflicts[0], true)[0]).toMatchObject({ id: null, targets: [{ id: null, rent: '200' }] })
  const missingTarget = rebaseCorrection([base], [mine], [{ ...base, targets: [] }])
  expect(resolveConflict(missingTarget.rows, missingTarget.conflicts[0], false)[0].targets).toEqual([])
})
