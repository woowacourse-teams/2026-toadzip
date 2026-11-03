import fixtures from './scenarios.json'
import type { JsonWorkspace } from '../../announcement-json/api'
import { blankRow } from '../../supply/supplyCorrectionApi'
import { readExtraction } from '../extraction'
import { createReview, validCellValue } from '../review'

/**
 * 한스빌 26B의 전환보증금만 다르게 한 추출 응답. 대상 조건이 달라 두 행을 하나로 접지 않고 공통 모집수 170으로 묶은 채 둔다.
 * 공통 모집 묶음과 행 여럿을 다루는 검토를 시험할 때 쓴다.
 */
export function separateHansville() {
  const extraction = structuredClone(fixtures.hansville.extraction)
  extraction.data.supplyRows[1].targets[0].convertedDeposit = 2000000
  return extraction
}

export function scenario(name: keyof typeof fixtures, extraction: unknown = fixtures[name].extraction) {
  const fixture = fixtures[name], extracted = readExtraction(JSON.stringify(extraction))
  if (!extracted.ok) throw new Error(extracted.error)
  const rows = fixture.rows.map(row => ({ ...blankRow(row.displayOrder), id: row.id, key: `row:${row.id}`,
    complexName: row.sourceComplexName, typeName: row.housingTypeName, pnu: row.pnu, category: 'RESUPPLY' as const,
    housingComplexId: row.complexId, housingTypeId: row.housingTypeId, householdCount: row.householdCount,
    exclusiveArea: row.exclusiveArea, supplyArea: row.supplyArea }))
  const workspace: JsonWorkspace = { snapshotToken: 'current', supply: { snapshotToken: 'supply', version: 1, rows, sources: [] },
    events: fixture.events.map(event => ({ id: event.id, data: event, complexName: event.complexName })),
    detail: { summary: { id: 29, name: '기존 공고', subtitle: '', provider: 'LH', rental: 'NATIONAL_RENTAL', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      data: { name: '기존 공고', rentalType: 'NATIONAL_RENTAL', recruitmentType: 'RESERVE', agencyCode: 'LH', originalUrl: 'https://example.com/notice' },
      sourceIdentifier: 'current-source', supplyRows: [], housingTypes: [], announcements: [], schedules: [], scheduleReviewed: false } }
  const housing = Object.fromEntries(fixture.complexes.map(complex => [complex.id, complex.housingTypes]))
  const review = createReview(extracted.value, workspace, housing)
  review.schedules.forEach(schedule => { schedule.include = schedule.cells.every(cell => validCellValue(cell.kind, cell.value) && !['notApplicable', 'unresolved'].includes(cell.state)) })
  return { review, workspace, housing }
}

/**
 * 대구테크노폴리스4: LH가 세 주택형을 통합행 하나(#747, 모집 240, 면적 미확인)로 내렸고 공고문은 051.9800A 200 · 059.9100A 25 · 059.9300B 15로 적었다.
 * lhSourceRow가 true면 추출 행이 모두 통합 LH 행 식별자(LH:P:SUPPLY:1)를 가리키고, 배열이면 행마다 그 식별자를 가리킨다.
 * unresolvedCount면 워커 이전 형식처럼 모집수를 '목록 모집수 240과 다름'으로 남긴다. unattached면 84 LH 행(LH:P:SUPPLY:2)에 공급행이 없다.
 * leadingLhRow면 통합 LH 행 앞에 다른 단지의 나눌 수 있는 LH 행(LH:P:SUPPLY:0, 공급행 #748)을 두어 목록 위치로 맞추면 틀린 행을 고르게 한다.
 * documentOwned면 공급정보가 공고문 소유라 작업실의 unattachedLhSupplyRows가 비어 있다.
 */
export function daegu({ lhSourceRow = false as boolean | (string | undefined)[], unresolvedCount = false, unattached = false, leadingLhRow = false, documentOwned = false } = {}) {
  const source = (index: number) => lhSourceRow === true ? 'LH:P:SUPPLY:1' : Array.isArray(lhSourceRow) ? lhSourceRow[index] : undefined
  const row = (name: string, area: number, count: number, index: number) => ({ complexReference: { sourceComplexName: '대구테크노폴리스4' }, sourceHousingTypeName: name,
    expectedMoveInMonth: null, totalSupplyHouseholdCount: count, exclusiveArea: area, supplyArea: null, ...(source(index) ? { lhSourceRow: source(index) } : {}),
    targets: [{ target: '일반', supplyRank: null, supplyHouseholdCount: null, reserveCount: null, rentalDeposit: 30000000, monthlyRent: 200000, convertedDeposit: null }] })
  const rows = [row('051.9800A', 51.98, 200, 0), row('059.9100A', 59.91, 25, 1), row('059.9300B', 59.93, 15, 2)]
  const extraction = { data: { announcement: {}, receptionPlace: {}, supplyRows: rows, schedules: [],
    unresolvedFields: unresolvedCount ? rows.map((_, index) => ({ path: `supplyRows[${index}].totalSupplyHouseholdCount`, reason: '목록 모집수 240과 다름' })) : [] },
    sharedCounts: [], notApplicable: [], evidence: rows.map((item, index) => ({ path: `supplyRows[${index}]`, page: 3, quote: `${item.sourceHousingTypeName} 468 ${item.totalSupplyHouseholdCount}` })) }
  const read = readExtraction(JSON.stringify(extraction))
  if (!read.ok) throw new Error(read.error)
  const merged = { ...blankRow(1), id: 747, key: 'row:747', complexName: '대구테크노폴리스4', typeName: '공공임대', pnu: '2771025000', category: 'RESUPPLY' as const,
    housingComplexId: 41, housingComplexName: '대구테크노폴리스4', householdCount: 240, areaOverride: false }
  const other = { ...merged, id: 748, key: 'row:748', complexName: '다른 단지', housingComplexId: 42, housingComplexName: '다른 단지', typeName: '39', householdCount: 10, exclusiveArea: 39, displayOrder: 2 }
  const lhSupplyRows = [...leadingLhRow ? [{ rowIdentifier: 'LH:P:SUPPLY:0', complexLabel: '다른 단지', typeName: '39', exclusiveArea: '39.0', householdCount: 10, attachedRowKey: 'row:748', splitRowKeys: [] }] : [],
    { rowIdentifier: 'LH:P:SUPPLY:1', complexLabel: '대구테크노폴리스4', typeName: '공공임대', exclusiveArea: null, householdCount: 240, attachedRowKey: 'row:747', splitRowKeys: [] },
    { rowIdentifier: 'LH:P:SUPPLY:2', complexLabel: '대구테크노폴리스4', typeName: '84', exclusiveArea: '84.0', householdCount: 5, attachedRowKey: unattached || leadingLhRow ? null : 'row:748', splitRowKeys: [] }]
  const workspace: JsonWorkspace = { snapshotToken: 'current', events: [],
    supply: { snapshotToken: 'supply', version: 1, rows: [merged, other], sources: [], lhSupplyRows, unattachedLhSupplyRows: documentOwned ? [] : lhSupplyRows.filter(lh => lh.attachedRowKey === null),
      rowSources: [{ rowKey: 'row:747', category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'LH:P:SUPPLY:1' }] },
    detail: { summary: { id: 30, name: '대구 공고', subtitle: '', provider: 'LH', rental: 'PUBLIC_RENTAL', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      data: { name: '대구 공고', rentalType: 'PUBLIC_RENTAL', recruitmentType: 'RESERVE', agencyCode: 'LH', originalUrl: 'https://example.com/daegu' },
      sourceIdentifier: 'daegu', supplyRows: [], housingTypes: [], announcements: [], schedules: [], scheduleReviewed: false } }
  const housing = { 41: [{ id: 501, name: '51A', exclusiveArea: 51.98, supplyArea: 72.1, householdCount: 468 }, { id: 502, name: '59A', exclusiveArea: 59.91, supplyArea: 80.2, householdCount: 120 },
    { id: 503, name: '59B', exclusiveArea: 59.93, supplyArea: 80.4, householdCount: 60 }], 42: [] }
  return { review: createReview(read.value, workspace, housing), workspace, housing }
}
