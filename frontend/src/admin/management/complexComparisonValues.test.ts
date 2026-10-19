import { expect, it } from 'vitest'
import { changedComparisonFields, comparisonFields, sourceValue, writeComparisonValue } from './complexComparisonValues'
import { type ComparisonSource } from './complexComparisonApi'

function field(name: string) { const value = comparisonFields.find(item => item.name === name); if (!value) throw new Error(name); return value }
function source(raw: Record<string, string | number | boolean | null>, category: ComparisonSource['category'] = 'MYHOME_COMPLEX'): ComparisonSource {
  return { category, sourceKey: 'test', automatic: false, evidenceNote: '확인', actor: 'admin', linkedAt: null,
    data: { id: 1, sourceKey: 'test', name: '원천', collectedAt: null, raw } }
}
it('마이홈의 유형과 시설값을 명시적으로 변환하며 0과 false를 보존한다', () => {
  const evidence = source({ instt_nm: '한국토지주택공사', suply_ty_nm: '50년임대', heat_mthd_detail_nm: '지역난방', house_ty_nm: '아파트', buld_stle_nm: '계단식', elvtr_instl_at_nm: '미설치', parkng_co: 0 })
  for (const [name, expected] of [['agencyCode', 'LH'], ['rentalType', 'PUBLIC_RENTAL_50Y'], ['heatingType', 'DISTRICT'], ['buildingType', 'APARTMENT'], ['corridorType', 'STAIR'], ['hasElevator', false], ['totalParkingCount', 0]] as const) {
    expect(sourceValue(field(name), evidence).value).toBe(expected)
  }
})
it('미제공·음수·알 수 없는 형식을 추정값으로 바꾸지 않는다', () => {
  expect(sourceValue(field('totalParkingCount'), source({ parkng_co: null })).value).toBeUndefined()
  expect(sourceValue(field('totalHouseholdCount'), source({ hshld_co: -1 })).value).toBeUndefined()
  expect(sourceValue(field('totalHouseholdCount'), source({ hshld_co: '12개 또는 15개' })).value).toBeUndefined()
  expect(sourceValue(field('hasElevator'), source({ elvtr_instl_at_nm: '확인 중' }))).toEqual({ raw: '확인 중', value: undefined })
})
it('LH 단지 전체 세대수와 주택형별 세대수를 섞지 않는다', () => {
  const evidence = source({ complex_total_unit_count: '120', total_unit_count: '30' }, 'LH_LEASE_CATALOG')
  expect(sourceValue(field('totalHouseholdCount'), evidence).value).toBe(120)
  expect(sourceValue(field('totalParkingCount'), evidence).value).toBeUndefined()
})
it('준공일은 실제 존재하는 날짜만 사용한다', () => {
  expect(sourceValue(field('completionDate'), source({ compet_de: '20240229' })).value).toBe('2024-02-29')
  expect(sourceValue(field('completionDate'), source({ compet_de: '20260229' })).value).toBeUndefined()
})
it('주소를 수정해도 다른 주소 항목을 지우지 않고 바뀐 항목만 계산한다', () => {
  const original = { version: 1, address: { roadAddress: '기존 주소', pnu: '111' }, totalParkingCount: 0, hasElevator: true }
  const next = writeComparisonValue(original, field('address.roadAddress'), '새 주소')
  expect(next.address).toEqual({ roadAddress: '새 주소', pnu: '111' })
  expect(original.address.roadAddress).toBe('기존 주소')
  expect(changedComparisonFields(original, next).map(value => value.name)).toEqual(['address.roadAddress'])
  expect(writeComparisonValue(original, field('hasElevator'), 'false').hasElevator).toBe(false)
})
