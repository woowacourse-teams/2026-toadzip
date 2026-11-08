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
 * 대구테크노폴리스4: 옛 LH 통합 공급행(#747 '공공임대', 모집 240, 면적 미확인)이 있고 공고문은 051.9800A 200 · 059.9100A 25 · 059.9300B 15로 적었다.
 * 통합행은 전용면적을 몰라 PDF 행과 맞추지 못하므로 PDF 행은 새 공급행이 되고 통합행은 'PDF에 없는 기존 공급행'으로 남는다.
 * LH 공급 원본의 240 한 줄은 그 통합행에 붙어 있고, 84 줄(LH:P:SUPPLY:2)은 같은 면적 공급행이 없어 붙지 않았다.
 * unresolvedCount면 워커 이전 형식처럼 모집수를 '목록 모집수 240과 다름'으로 남긴다.
 */
export function daegu({ unresolvedCount = false } = {}) {
  const row = (name: string, area: number, count: number) => ({ complexReference: { sourceComplexName: '대구테크노폴리스4' }, sourceHousingTypeName: name,
    expectedMoveInMonth: null, totalSupplyHouseholdCount: count, exclusiveArea: area, supplyArea: null, sourceRow: null,
    targets: [{ target: '일반', supplyRank: null, supplyHouseholdCount: null, reserveCount: null, rentalDeposit: 30000000, monthlyRent: 200000, convertedDeposit: null }] })
  const rows = [row('051.9800A', 51.98, 200), row('059.9100A', 59.91, 25), row('059.9300B', 59.93, 15)]
  const extraction = { data: { announcement: {}, receptionPlace: {}, supplyRows: rows, schedules: [],
    unresolvedFields: unresolvedCount ? rows.map((_, index) => ({ path: `supplyRows[${index}].totalSupplyHouseholdCount`, reason: '목록 모집수 240과 다름' })) : [] },
    sharedCounts: [], notApplicable: [], evidence: rows.map((item, index) => ({ path: `supplyRows[${index}]`, page: 3, quote: `${item.sourceHousingTypeName} 468 ${item.totalSupplyHouseholdCount}` })) }
  const read = readExtraction(JSON.stringify(extraction))
  if (!read.ok) throw new Error(read.error)
  const merged = { ...blankRow(1), id: 747, key: 'row:747', complexName: '대구테크노폴리스4', typeName: '공공임대', pnu: '2771025000', category: 'RESUPPLY' as const,
    housingComplexId: 41, housingComplexName: '대구테크노폴리스4', householdCount: 240, areaOverride: false }
  const other = { ...merged, id: 748, key: 'row:748', complexName: '다른 단지', housingComplexId: 42, housingComplexName: '다른 단지', typeName: '39', householdCount: 10, exclusiveArea: 39, displayOrder: 2 }
  const lhSupplyRows = [{ rowIdentifier: 'LH:P:SUPPLY:1', complexLabel: '대구테크노폴리스4', typeName: '공공임대', exclusiveArea: null, householdCount: 240, attachedRowKey: 'row:747', ambiguous: false },
    { rowIdentifier: 'LH:P:SUPPLY:2', complexLabel: '대구테크노폴리스4', typeName: '84', exclusiveArea: '84.0', householdCount: 5, attachedRowKey: null, ambiguous: false }]
  const workspace: JsonWorkspace = { snapshotToken: 'current', events: [],
    supply: { snapshotToken: 'supply', version: 1, rows: [merged, other], sources: [], lhSupplyRows, unattachedLhSupplyRows: lhSupplyRows.filter(lh => lh.attachedRowKey === null),
      rowSources: [{ rowKey: 'row:747', category: 'LH_ANNOUNCEMENT_SUPPLY', sourceKey: 'LH:P:SUPPLY:1' }] },
    detail: { summary: { id: 30, name: '대구 공고', subtitle: '', provider: 'LH', rental: 'PUBLIC_RENTAL', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      data: { name: '대구 공고', rentalType: 'PUBLIC_RENTAL', recruitmentType: 'RESERVE', agencyCode: 'LH', originalUrl: 'https://example.com/daegu' },
      sourceIdentifier: 'daegu', supplyRows: [], housingTypes: [], announcements: [], schedules: [], scheduleReviewed: false } }
  const housing = { 41: [{ id: 501, name: '51A', exclusiveArea: 51.98, supplyArea: 72.1, householdCount: 468 }, { id: 502, name: '59A', exclusiveArea: 59.91, supplyArea: 80.2, householdCount: 120 },
    { id: 503, name: '59B', exclusiveArea: 59.93, supplyArea: 80.4, householdCount: 60 }], 42: [] }
  return { review: createReview(read.value, workspace, housing), workspace, housing }
}

/**
 * GH 수원 광교 지구: 정제가 GH 주택형마다 공급행 틀(#901 '36A 청년', #902 '36C 신혼부부')을 만들었고 면적은 아직 없다.
 * 공고문은 36A 20 · 36C 10으로 적었다. 표기('36A' ↔ '36A 청년')와 면적으로는 틀 행을 고를 수 없다.
 * sourceRow면 추출 행이 워커가 맞춘 GH 틀 행 키를 가리킨다.
 */
export function ghFrame({ sourceRow = false } = {}) {
  const frame = (id: number, typeName: string, count: number) => ({ ...blankRow(id - 901), id, key: `row:${String(id)}`, complexName: '수원 광교', typeName, pnu: '4111710100',
    category: 'RESUPPLY' as const, housingComplexId: 51, housingComplexName: '광교 행복주택', householdCount: count, areaOverride: false })
  const row = (name: string, area: number, count: number, key: string) => ({ complexReference: { sourceComplexName: '수원 광교' }, sourceHousingTypeName: name,
    expectedMoveInMonth: null, totalSupplyHouseholdCount: count, exclusiveArea: area, supplyArea: null, ...(sourceRow ? { sourceRow: key } : {}),
    targets: [{ target: '청년', supplyRank: null, supplyHouseholdCount: null, reserveCount: null, rentalDeposit: 30000000, monthlyRent: 200000, convertedDeposit: null }] })
  const rows = [row('36A', 36.7, 20, 'GH:801:수원 광교:36A'), row('36C', 36.83, 10, 'GH:801:수원 광교:36C')]
  const extraction = { data: { announcement: {}, receptionPlace: {}, supplyRows: rows, schedules: [], unresolvedFields: [] },
    sharedCounts: [], notApplicable: [], evidence: rows.map((item, index) => ({ path: `supplyRows[${index}]`, page: 2, quote: `${item.sourceHousingTypeName} ${item.totalSupplyHouseholdCount}` })) }
  const read = readExtraction(JSON.stringify(extraction))
  if (!read.ok) throw new Error(read.error)
  const workspace: JsonWorkspace = { snapshotToken: 'current', events: [],
    supply: { snapshotToken: 'supply', version: 1, rows: [frame(901, '36A 청년', 20), frame(902, '36C 신혼부부', 10)], sources: [], lhSupplyRows: [], unattachedLhSupplyRows: [],
      rowSources: [{ rowKey: 'row:901', category: 'GH_ANNOUNCEMENT', sourceKey: 'GH:801:수원 광교:36A' }, { rowKey: 'row:902', category: 'GH_ANNOUNCEMENT', sourceKey: 'GH:801:수원 광교:36C' }] },
    detail: { summary: { id: 31, name: 'GH 공고', subtitle: '', provider: 'GH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      data: { name: 'GH 공고', rentalType: 'HAPPY_HOUSING', recruitmentType: 'RESERVE', agencyCode: 'GH', originalUrl: 'https://example.com/gh' },
      sourceIdentifier: 'GH:801', supplyRows: [], housingTypes: [], announcements: [], schedules: [], scheduleReviewed: false } }
  const housing = { 51: [{ id: 601, name: '36A', exclusiveArea: 36.7, supplyArea: 50.1, householdCount: 100 }, { id: 602, name: '36C', exclusiveArea: 36.83, supplyArea: 50.3, householdCount: 80 }] }
  return { read: read.value, review: createReview(read.value, workspace, housing), workspace, housing }
}

/**
 * GH 다산역 A2 지구: GH 정제가 청약구분·신청자마다 공급행을 나눠(#911 '24A,B,C 청년' 20 · #912 '24B 주거급여수급자' 3 · #913 '24C 고령자' 3)
 * 면적 없이 만들었다. 공고문은 세 청약구분을 같은 전용 24.97㎡로 적고 모집수는 대상별 예비자 수만 적었다.
 * 워커는 PDF 행마다 GH 틀 행을 하나씩 맞춘다(sourceRow).
 */
/** GH 다산역A2의 같은 전용면적(24.97㎡) PDF 행 셋과 GH 틀 행 셋. names는 PDF 행의 주택형(신청형) 표기다. */
export function ghSameArea(names: [string, string, string] = ['24ABC', '24B', '24C']) {
  const frame = (id: number, typeName: string, count: number) => ({ ...blankRow(id - 911), id, key: `row:${String(id)}`, complexName: '남양주 다산역A2 경기행복주택', typeName,
    pnu: '4136010100', category: 'RESUPPLY' as const, housingComplexId: 52, housingComplexName: '다산역A2', householdCount: count, areaOverride: false })
  const row = (name: string, target: string, reserve: number, code: string) => ({ complexReference: { sourceComplexName: '다산역 A2' }, sourceHousingTypeName: name,
    expectedMoveInMonth: null, totalSupplyHouseholdCount: null, exclusiveArea: 24.97, supplyArea: null, sourceRow: `GH:839:남양주 다산역A2 경기행복주택:${code}`,
    targets: [{ target, supplyRank: null, supplyHouseholdCount: null, reserveCount: reserve, rentalDeposit: 46920000, monthlyRent: 178670, convertedDeposit: null }] })
  const rows = [row(names[0], '청년', 20, '24A,B,C'), row(names[1], '주거급여 수급자', 3, '24B'), row(names[2], '고령자', 3, '24C')]
  const extraction = { data: { announcement: {}, receptionPlace: {}, supplyRows: rows, schedules: [], unresolvedFields: [] },
    sharedCounts: [], notApplicable: [], evidence: rows.map((item, index) => ({ path: `supplyRows[${index}]`, page: 4, quote: `${item.sourceHousingTypeName} ${item.targets[0].target}` })) }
  const read = readExtraction(JSON.stringify(extraction))
  if (!read.ok) throw new Error(read.error)
  const frames = [frame(911, '24A,B,C 청년', 20), frame(912, '24B 주거급여수급자', 3), frame(913, '24C 고령자', 3)]
  const workspace: JsonWorkspace = { snapshotToken: 'current', events: [],
    supply: { snapshotToken: 'supply', version: 1, rows: frames, sources: [], lhSupplyRows: [], unattachedLhSupplyRows: [],
      rowSources: [['row:911', '24A,B,C'], ['row:912', '24B'], ['row:913', '24C']].map(([rowKey, code]) => ({ rowKey, category: 'GH_ANNOUNCEMENT', sourceKey: `GH:839:남양주 다산역A2 경기행복주택:${code}` })) },
    detail: { summary: { id: 57, name: 'GH 공고', subtitle: '', provider: 'GH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      data: { name: 'GH 공고', rentalType: 'HAPPY_HOUSING', recruitmentType: 'RESERVE', agencyCode: 'GH', originalUrl: 'https://example.com/gh' },
      sourceIdentifier: 'GH:839', supplyRows: [], housingTypes: [], announcements: [], schedules: [], scheduleReviewed: false } }
  const housing = { 52: [{ id: 611, name: '24A', exclusiveArea: 24.97, supplyArea: 40.34, householdCount: 300 }] }
  return { read: read.value, review: createReview(read.value, workspace, housing), workspace, housing }
}
