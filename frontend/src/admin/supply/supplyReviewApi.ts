import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const reviewFieldLabels: Record<string, string> = {
  connection: '연결한 단지·주택형과 공통정보', names: '공고별 단지명·형명·지번', areas: '공고별 면적 (㎡)',
  householdCount: '금회 모집수 (세대)', sharedRecruitment: '공통 모집 묶음·수량', moveInMonth: '입주예정월',
  category: '공급구분', excluded: '공개 제외 여부', displayOrder: '표시 순서', targetCountsComparable: '대상별 수량 집계 범위',
  target: '공급대상', supplyRank: '순위', reserveCount: '예비자수 (명)', deposit: '보증금 (원)', rent: '월세 (원)',
  convertedDeposit: '전환보증금 (원)', condition: '신청조건', parentExcluded: '상위 공급행 제외 여부',
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
