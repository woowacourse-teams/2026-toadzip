import { expect, it } from 'vitest'
import { mergeBlocked, mergeInto, possibleLhDuplicates, suggestedMergeTarget, unattachedLhNotice } from './lhSupplyOwnership'
import type { CorrectionRow, SupplyWorkspace } from './supplyCorrectionApi'

function row(id: number, complexId: number, typeName: string): CorrectionRow {
  return { id, key: `row:${id}`, housingComplexId: complexId, housingComplexName: `단지 ${complexId}`, housingTypeId: 1, newHousingType: null,
    groupId: null, sharedHouseholdCount: null, protectedValues: false, excluded: false, displayOrder: id, complexName: `단지 ${complexId}`,
    typeName, pnu: '1', moveInMonth: null, category: 'NEW_SUPPLY', householdCount: 20, areaOverride: false, exclusiveArea: null,
    supplyArea: null, targets: [], targetCountsComparable: false }
}

it('LH로 펼친 행이 있는 단지에 직접 추가한 행만 중복 후보로 고른다', () => {
  const workspace: SupplyWorkspace = { snapshotToken: 't', version: 1, sources: [],
    rows: [row(59, 1, 'A-1'), row(262, 1, 'B-1'), row(256, 1, '55B-1'), row(300, 2, '26')],
    rowSources: [{ rowKey: 'row:59', category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'LH:P:SUPPLY:0' },
      { rowKey: 'row:262', category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'LH:P:SUPPLY:1' }],
    deletableRowKeys: ['row:256', 'row:300'] }

  expect(possibleLhDuplicates(workspace).map(item => item.id)).toEqual([256])
  expect(unattachedLhNotice(workspace)).toBe('')
})

const target = (rent: string, excluded = false) => ({ id: 9, key: `target:${rent}`, excluded, protectedValues: false, displayOrder: 0, target: '일반', supplyRank: null,
  householdCount: null, reserveCount: null, deposit: '9330000', rent, convertedDeposit: null, condition: null })

it('같은 주택형에 연결된 후보가 하나일 때만 합칠 행으로 제안한다', () => {
  const duplicate = row(205, 1, '39')
  const sameType = { ...row(264, 1, '39'), housingTypeId: 1 }
  expect(suggestedMergeTarget([sameType, { ...row(91, 1, '27'), housingTypeId: 2 }], duplicate)).toBe(sameType)
  expect(suggestedMergeTarget([sameType, { ...row(265, 1, '39'), housingTypeId: 1 }], duplicate)).toBeNull()
  expect(suggestedMergeTarget([{ ...row(91, 1, '27'), housingTypeId: 2 }], duplicate)).toBeNull()
})

it('관리자 확인 값의 비어 있지 않은 칸과 제외하지 않은 임대조건만 펼침 행으로 옮긴다', () => {
  const duplicate = { ...row(256, 1, '55B-1(주거약자)'), householdCount: null, moveInMonth: '2027-03', targets: [target('342610'), target('1', true)] }
  const expanded = { ...row(262, 1, 'B-1(주거약자)'), householdCount: 20, targets: [] }

  const merged = mergeInto(expanded, duplicate, () => 'new-key')

  expect(merged).toMatchObject({ id: 262, key: 'row:262', typeName: '55B-1(주거약자)', householdCount: 20, moveInMonth: '2027-03', housingTypeId: 1 })
  expect(merged.targets).toEqual([{ ...target('342610'), id: null, key: 'new-key', displayOrder: 0 }])
})

it('펼침 행에 제외하지 않은 임대조건이 있으면 합치기를 막는다', () => {
  expect(mergeBlocked({ ...row(262, 1, '39'), targets: [target('100')] })).toBe(true)
  expect(mergeBlocked({ ...row(262, 1, '39'), targets: [target('100', true)] })).toBe(false)
})
