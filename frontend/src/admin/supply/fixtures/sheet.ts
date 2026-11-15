import type { SheetHousingType, SupplySheet } from '../supplySheet'

/**
 * 테스트용 공급표. 두꺼비 단지 46A(일반·고령자)와 59B(일반).
 * 46A 모집 10은 46A의 두 칸이 함께 쓰는 주택형 모집수, 46A 고령자·59B 일반은 예비 8을 함께 쓴다(여러 주택형에 걸친 수).
 */
export function housingType(fields: Partial<SheetHousingType> & Pick<SheetHousingType, 'key' | 'name'>): SheetHousingType {
  return { id: Number(fields.key.split(':')[1]) || null, announcementComplexId: 5, complexName: '두꺼비', housingComplexId: 11, housingComplexName: '두꺼비 단지',
    housingTypeId: null, newHousingType: null, exclusiveArea: 46.5, supplyArea: null, expectedMoveInMonth: null, supplyPnu: null, category: null,
    displayOrder: 0, adminModified: false, ...fields }
}
export function sampleSheet(): SupplySheet {
  return {
    housingTypes: [housingType({ key: 'row:1', name: '46A', housingTypeId: 21 }), housingType({ key: 'row:2', name: '59B', housingTypeId: 22, exclusiveArea: 59.1, displayOrder: 1 })],
    targets: [{ key: 'target:1', id: 1, label: '일반', applicationCondition: '', groups: ['GENERAL'], displayOrder: 0 }, { key: 'target:2', id: 2, label: '고령자', applicationCondition: '', groups: ['ELDERLY'], displayOrder: 1 }],
    cells: [{ key: 'cell:1', id: 1, housingType: 'row:1', target: 'target:1', displayOrder: 0 }, { key: 'cell:2', id: 2, housingType: 'row:1', target: 'target:2', displayOrder: 1 },
      { key: 'cell:3', id: 3, housingType: 'row:2', target: 'target:1', displayOrder: 0 }],
    quotas: [{ key: 'quota:1', id: 1, kind: 'WINNER', count: 10, members: ['cell:1', 'cell:2'], adminModified: false },
      { key: 'quota:2', id: 2, kind: 'RESERVE', count: 5, members: ['cell:1'], adminModified: false },
      { key: 'quota:3', id: 3, kind: 'WINNER', count: 7, members: ['cell:3'], adminModified: false },
      { key: 'quota:4', id: 4, kind: 'RESERVE', count: 8, members: ['cell:2', 'cell:3'], adminModified: false }],
    leaseTerms: [{ key: 'lease:1', id: 1, rentalDeposit: '1000000', monthlyRent: '50000', convertedDeposit: null, members: ['cell:1', 'cell:2'], adminModified: false },
      { key: 'lease:2', id: 2, rentalDeposit: '2000000', monthlyRent: '80000', convertedDeposit: null, members: ['cell:3'], adminModified: false }],
  }
}
