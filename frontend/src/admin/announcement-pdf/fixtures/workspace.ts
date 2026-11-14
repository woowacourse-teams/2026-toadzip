import fixtures from './scenarios.json'
import type { JsonWorkspace } from '../../announcement-json/api'
import type { SupplySheet } from '../../supply/supplySheet'
import { readExtraction, type Extraction } from '../extraction'
import { createReview, pdfSheet, validCellValue } from '../review'
import type { HousingCandidates } from '../matching'

export function extractionOf(value: unknown): Extraction {
  const read = readExtraction(JSON.stringify(value))
  if (!read.ok) throw new Error(read.error)
  return read.value
}

type FixtureRow = { id: number; displayOrder: number; sourceComplexName: string; housingTypeName: string; pnu: string | null; householdCount: number | null
  exclusiveArea: number | null; supplyArea: number | null; complexId: number | null; housingTypeId: number | null }
/** 지금 공급표. 시나리오의 저장된 행마다 주택형 하나, 공급대상 '일반' 칸 하나, 그 칸의 모집 묶음이다. */
export function rowsSheet(rows: FixtureRow[], complexName = (row: FixtureRow) => row.sourceComplexName): SupplySheet {
  const sheet: SupplySheet = { housingTypes: [], targets: [{ key: 'target:1', id: 1, label: '일반', applicationCondition: '', displayOrder: 0 }], cells: [], quotas: [], leaseTerms: [] }
  rows.forEach((row, at) => {
    const key = `row:${row.id}`, cell = `cell:${row.id}`
    sheet.housingTypes.push({ key, id: row.id, announcementComplexId: null, complexName: row.sourceComplexName, housingComplexId: row.complexId, housingComplexName: row.complexId === null ? null : complexName(row),
      housingTypeId: row.housingTypeId, name: row.housingTypeName, exclusiveArea: row.exclusiveArea, supplyArea: row.supplyArea, expectedMoveInMonth: null,
      supplyPnu: row.pnu, category: 'RESUPPLY', displayOrder: at, adminModified: false })
    sheet.cells.push({ key: cell, id: row.id, housingType: key, target: 'target:1', displayOrder: 0 })
    if (row.householdCount !== null) sheet.quotas.push({ key: `quota:${row.id}`, id: row.id, kind: 'WINNER', count: row.householdCount, members: [cell], adminModified: false })
  })
  return sheet
}

/**
 * 이 공고문을 이미 반영한 지금 공급표. PDF 공급표의 키(pdf:…)를 저장된 키(row:·target:·cell:)로 바꾸고 단지를 연결한다.
 * 값을 바꿔 변경·보호 값을 시험한다.
 */
export function savedSheet(extraction: Extraction, complex: { id: number; name: string }): SupplySheet {
  const pdf = pdfSheet(extraction), id = (key: string) => Number(/\d+$/.exec(key)![0]) + 1
  const rename = (key: string) => key.replace(/^pdf:t(\d+)$/, (_, at) => `row:${Number(at) + 1}`).replace(/^pdf:g(\d+)$/, (_, at) => `target:${Number(at) + 1}`).replace(/^pdf:c(\d+)$/, (_, at) => `cell:${Number(at) + 1}`)
  return {
    housingTypes: pdf.housingTypes.map(type => ({ ...type, key: rename(type.key), id: id(type.key), housingComplexId: complex.id, housingComplexName: complex.name, supplyPnu: '1111010100', category: 'RESUPPLY' })),
    targets: pdf.targets.map(target => ({ ...target, key: rename(target.key), id: id(target.key) })),
    cells: pdf.cells.map(cell => ({ ...cell, key: rename(cell.key), id: id(cell.key), housingType: rename(cell.housingType), target: rename(cell.target) })),
    quotas: pdf.quotas.map((quota, at) => ({ ...quota, key: `quota:${at + 1}`, id: at + 1, members: quota.members.map(rename) })),
    leaseTerms: pdf.leaseTerms.map((lease, at) => ({ ...lease, key: `lease:${at + 1}`, id: at + 1, members: lease.members.map(rename) })),
  }
}

export function workspaceOf(sheet: SupplySheet, { id = 29, events = [] as JsonWorkspace['events'] } = {}): JsonWorkspace {
  return { snapshotToken: 'current', events,
    supply: { snapshotToken: 'supply', version: 1, sheet, announcementComplexes: [], applicationTypeNames: [], sourceReference: [], warnings: [] },
    detail: { summary: { id, name: '기존 공고', subtitle: '', provider: 'LH', rental: 'NATIONAL_RENTAL', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
      data: { name: '기존 공고', rentalType: 'NATIONAL_RENTAL', recruitmentType: 'RESERVE', agencyCode: 'LH', originalUrl: 'https://example.com/notice' },
      sourceIdentifier: 'current-source', housingTypes: [], supplySheet: sheet, announcements: [], schedules: [], scheduleReviewed: false } }
}

/**
 * 시나리오 공고. 지금 공급표는 시나리오의 저장된 행(rowsSheet)이고, sheet를 주면 그 공급표다.
 * hansville: 26A·26B가 일반 모집 170을 함께 쓴다(다른 주택형과 함께 쓰는 수). 지금 공급표는 단지 이름 없는 '아파트' 170 하나다.
 */
export function scenario(name: keyof typeof fixtures, extraction: unknown = fixtures[name].extraction, sheet?: (extracted: Extraction) => SupplySheet) {
  const fixture = fixtures[name], extracted = extractionOf(extraction)
  const workspace = workspaceOf(sheet ? sheet(extracted) : rowsSheet(fixture.rows as FixtureRow[]), { events: fixture.events.map(event => ({ id: event.id, data: event, complexName: event.complexName })) })
  const housing: HousingCandidates = Object.fromEntries(fixture.complexes.map(complex => [complex.id, complex.housingTypes]))
  const review = createReview(extracted, workspace, housing)
  review.schedules.forEach(schedule => { schedule.include = schedule.cells.every(cell => validCellValue(cell.kind, cell.value) && !['notApplicable', 'unresolved'].includes(cell.state)) })
  return { review, workspace, housing, extraction: extracted }
}

/** 워커 공급표 응답 하나. 주택형마다 공급대상 '일반' 칸 하나와 그 칸의 모집 수·임대조건이다. */
export function sheetResponse(complexName: string, types: { name: string; area: number; count: number; deposit?: number; rent?: number }[], page = 3) {
  const key = (at: number) => ({ housingType: `h${at}`, target: 't0' })
  return { data: { announcement: {}, receptionPlace: {}, schedules: [], unresolvedFields: [],
    supplySheet: {
      housingTypes: types.map((type, at) => ({ key: `h${at}`, complexName, name: type.name, exclusiveArea: type.area, commonArea: null, supplyArea: null, constructedHouseholdCount: null, expectedMoveInMonth: null, houseTypes: [] })),
      targets: [{ key: 't0', label: '일반', applicationCondition: null }],
      cells: types.map((_, at) => key(at)),
      quotas: types.map((type, at) => ({ kind: 'WINNER', count: type.count, members: [key(at)] })),
      leaseTerms: types.map((type, at) => ({ rentalDeposit: type.deposit ?? 30000000, monthlyRent: type.rent ?? 200000, convertedDeposit: null, members: [key(at)] })),
      unresolvedFields: [],
      evidence: types.flatMap((type, at) => [{ path: `housingTypes[${at}]`, page, quote: `${type.name} ${type.area} ${type.count}` }, { path: `quotas[${at}].count`, page, quote: `${type.name} ${type.count}` },
        { path: `leaseTerms[${at}]`, page, quote: `${type.deposit ?? 30000000} ${type.rent ?? 200000}` }]),
    } },
  notApplicable: [], evidence: [] }
}

/**
 * 대구테크노폴리스4: 지금 공급표에는 옛 LH 통합 주택형('공공임대' 240, 면적 미확인)이 있고 공고문은 051.9800A 200 · 059.9100A 25 · 059.9300B 15로 적었다.
 * 공고 단지가 단지 41에 연결돼 있다. 이름이 다르므로 PDF 주택형 셋은 새로 더하고 '공공임대'는 저장하면 지운다. 다른 단지의 주택형('39')도 PDF에 없어 지운다.
 */
export function daegu() {
  const extraction = extractionOf(sheetResponse('대구테크노폴리스4', [{ name: '051.9800A', area: 51.98, count: 200 }, { name: '059.9100A', area: 59.91, count: 25 }, { name: '059.9300B', area: 59.93, count: 15 }]))
  const sheet = rowsSheet([
    { id: 747, displayOrder: 0, sourceComplexName: '대구테크노폴리스4', housingTypeName: '공공임대', pnu: '2771025000', householdCount: 240, exclusiveArea: null, supplyArea: null, complexId: 41, housingTypeId: null },
    { id: 748, displayOrder: 1, sourceComplexName: '다른 단지', housingTypeName: '39', pnu: null, householdCount: 10, exclusiveArea: 39, supplyArea: null, complexId: 42, housingTypeId: null }],
  row => row.sourceComplexName)
  const workspace = workspaceOf(sheet, { id: 30 })
  // 공고 단지가 단지 41에 연결돼 있어 PDF 단지 이름으로 단지가 정해진다.
  workspace.supply.announcementComplexes = [{ id: 7, name: '대구테크노폴리스4', roadAddress: null, housingComplexId: 41, housingComplexName: '대구테크노폴리스4', linkMethod: 'ADMIN', rowCount: 1 }]
  const housing: HousingCandidates = { 41: [{ id: 501, name: '51A', exclusiveArea: 51.98, supplyArea: 72.1, householdCount: 468 }, { id: 502, name: '59A', exclusiveArea: 59.91, supplyArea: 80.2, householdCount: 120 },
    { id: 503, name: '59B', exclusiveArea: 59.93, supplyArea: 80.4, householdCount: 60 }], 42: [] }
  return { review: createReview(extraction, workspace, housing), workspace, housing, extraction }
}
