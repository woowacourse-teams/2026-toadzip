import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 검토 값의 항목 이름. row:{id}는 주택형 값, cell:{id}는 공급대상 칸의 모집·예비 수와 임대조건이다. 모르는 항목은 이름 그대로 보인다. */
export const reviewFieldLabels: Record<string, string> = {
  connection: '연결한 단지·주택형', housingComplexId: '연결 단지', housingTypeId: '연결 주택형', complexName: '공고의 단지명', name: '공고의 주택형 표기',
  exclusiveArea: '공고별 전용면적 (㎡)', supplyArea: '공고별 공급면적 (㎡)', supplyPnu: '공급 PNU', expectedMoveInMonth: '입주 예정 연월',
  category: '공급 구분', displayOrder: '표시 순서', householdCount: '주택형 모집수 (세대)',
  label: '공급대상', target: '공급대상', applicationCondition: '신청조건', winnerCount: '모집 수 (세대)', reserveCount: '예비 수 (명)',
  rentalDeposit: '보증금 (원)', monthlyRent: '월 임대료 (원)', convertedDeposit: '전환 보증금 (원)',
}
export type ReviewSourceScope = { category: string; sourceKey: string; fields: string[] }
export type SupplyReviewInput = { snapshotToken: string; target: string; fields: string[]; outcome: 'VERIFIED' | 'ON_HOLD'; note: string; sourceUrl: string; sourcePage: string | null; sources: ReviewSourceScope[] }
export type SupplyReviewRecord = { id: number; target: string; outcome: string; status: string; latest: boolean;
  checkedValues: Record<string, unknown>; currentValues: Record<string, unknown> | null; evidence: unknown[]; currentEvidence: unknown[];
  note: string; sourceUrl: string; sourcePage: string | null; actor: string; reviewedAt: string }
export type SupplyReviewOverview = { values: Record<string, Record<string, unknown>>; records: SupplyReviewRecord[] }
function parse(value: unknown): SupplyReviewOverview {
  if (!isRecord(value) || !isRecord(value.values) || !Object.values(value.values).every(isRecord) || !Array.isArray(value.records)) throw new Error('검토 응답 형식이 올바르지 않습니다.')
  for (const record of value.records) {
    if (!isRecord(record) || typeof record.id !== 'number' || typeof record.target !== 'string' || typeof record.latest !== 'boolean'
      || !['VERIFIED', 'ON_HOLD'].includes(String(record.outcome)) || !['VERIFIED', 'ON_HOLD', 'STALE'].includes(String(record.status))
      || !isRecord(record.checkedValues) || !(record.currentValues === null || isRecord(record.currentValues))
      || !Array.isArray(record.evidence) || !Array.isArray(record.currentEvidence)
      || !['note', 'sourceUrl', 'actor', 'reviewedAt'].every(field => typeof record[field] === 'string')
      || !(record.sourcePage === null || typeof record.sourcePage === 'string')) throw new Error('검토 기록 형식이 올바르지 않습니다.')
  }
  return value as SupplyReviewOverview
}
export async function loadSupplyReviews(id: string, signal?: AbortSignal) {
  return parse(await requestManagementApi(`/api/admin/announcements/${id}/supply-corrections/reviews`, 'GET', undefined, signal))
}
export async function saveSupplyReview(id: string, input: SupplyReviewInput) {
  return parse(await requestManagementApi(`/api/admin/announcements/${id}/supply-corrections/reviews`, 'POST', input))
}
