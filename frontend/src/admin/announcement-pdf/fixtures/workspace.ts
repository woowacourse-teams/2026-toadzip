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
