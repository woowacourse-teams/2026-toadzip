import { expect, it } from 'vitest'
import { blankRow, blankTarget, parseSupplyWorkspace, type CorrectionRow } from './supplyCorrectionApi'
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

it('대표 주택형을 바꾸며 함께 모집하는 주택형 목록을 비운 행은 최신값의 목록으로 되돌리지 않는다', () => {
  const base = { ...blankRow(0), id: 1, key: 'row:1', housingComplexId: 7, housingTypeId: 2, linkedHousingTypes: [{ housingTypeId: 3 }] }
  const mine = { ...base, housingTypeId: 4, linkedHousingTypes: [] }
  const latest = { ...base, householdCount: 20 }
  const sent = JSON.parse(JSON.stringify(rebaseCorrection([base], [mine], [latest]).rows[0])) as Record<string, unknown>
  expect(sent).toMatchObject({ housingTypeId: 4, householdCount: 20, linkedHousingTypes: [] })
})

it('PDF로 채운 함께 모집하는 주택형 목록은 최신값 위에 다시 맞춰도 유지한다', () => {
  const base = { ...blankRow(0), id: 1, key: 'row:1', housingComplexId: 7, housingTypeId: 2 }
  const linked = [{ housingTypeId: 3 }, { newHousingType: { name: '26C', exclusiveArea: 26.2, supplyArea: null } }]
  const mine = { ...base, linkedHousingTypes: linked }
  const result = rebaseCorrection([base], [mine], [{ ...base, householdCount: 20 }])
  expect(result.rows[0]).toMatchObject({ householdCount: 20, linkedHousingTypes: linked })
  expect(result.conflicts).toEqual([])
})

it('조회한 연결을 뺐다가 같은 주택형을 다시 넣은 행은 다른 사람이 바꾼 연결과 충돌하지 않는다', () => {
  const stored = (row: CorrectionRow) => parseSupplyWorkspace(structuredClone({ snapshotToken: 't', version: 1, rows: [row], sources: [] })).rows[0]
  const base = stored({ ...blankRow(0), id: 1, key: 'row:1', housingComplexId: 7, housingTypeId: 2, linkedHousingTypes: [{ housingTypeId: 3, newHousingType: null }] })
  const mine = { ...base, linkedHousingTypes: [{ housingTypeId: 3 }] }
  const latest = stored({ ...base, linkedHousingTypes: [{ housingTypeId: 4, newHousingType: null }] })
  const result = rebaseCorrection([base], [mine], [latest])
  expect(result.conflicts).toEqual([])
  expect(result.rows[0].linkedHousingTypes).toEqual([{ housingTypeId: 4 }])
})
