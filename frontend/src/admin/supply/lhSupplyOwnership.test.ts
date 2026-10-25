import { expect, it } from 'vitest'
import { possibleLhDuplicates, unattachedLhNotice } from './lhSupplyOwnership'
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
