import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const qualityReasons: Record<string, string> = {
  MISSING: '정보 누락', SOURCE_MISMATCH: '수집값 불일치', STALE: '확인값 변경',
  UNREVIEWED: '아직 미검토', ON_HOLD: '검토 보류', SOURCE_CHANGED: '원천 변경·소실',
}
/** 원천 변경·소실의 출처. 데이터 검증에서 확인한 원천의 변경과 공급행 원천의 소실을 구분한다. */
export type SourceChange = 'COMPARISON_CHECK' | 'SUPPLY_SOURCE'
export type QualityItem = { resource: 'COMPLEX' | 'ANNOUNCEMENT'; id: number; name: string;
  reasons: string[]; missingFields: string[]; sourceChanges: SourceChange[]; updatedAt: string | null }
export type QualityPage = { items: QualityItem[]; page: number; totalElements: number; totalPages: number; hasNext: boolean }

export async function getQualityQueue(params: URLSearchParams, signal: AbortSignal): Promise<QualityPage> {
  const result = await requestManagementApi(`/api/admin/quality/queue?${params}`, 'GET', undefined, signal)
  if (!isRecord(result) || !Array.isArray(result.items) || !Number.isSafeInteger(result.page)
    || !Number.isSafeInteger(result.totalElements) || !Number.isSafeInteger(result.totalPages)
    || typeof result.hasNext !== 'boolean') throw new Error('검토 목록 응답을 확인해 주세요.')
  for (const item of result.items) {
    if (!isRecord(item) || !['COMPLEX', 'ANNOUNCEMENT'].includes(String(item.resource))
      || !Number.isSafeInteger(item.id) || Number(item.id) < 1 || typeof item.name !== 'string'
      || !Array.isArray(item.reasons) || !item.reasons.every(reason => typeof reason === 'string' && reason in qualityReasons)
      || !Array.isArray(item.missingFields) || !item.missingFields.every(field => typeof field === 'string')
      || !Array.isArray(item.sourceChanges) || !item.sourceChanges.every(origin => origin === 'COMPARISON_CHECK' || origin === 'SUPPLY_SOURCE')
      || !(item.updatedAt === null || typeof item.updatedAt === 'string' && Number.isFinite(Date.parse(item.updatedAt)))) {
      throw new Error('검토 항목 응답을 확인해 주세요.')
    }
  }
  return result as QualityPage
}

const announcementFieldSections: Record<string, string> = {
  '단지·주택형 연결': 'relations', '공급정보': 'relations', '확인된 접수 일정': 'schedules',
}
const sectionOrder = ['relations', 'schedules', 'verification']

/** 빠진 항목을 고칠 상세 탭. 공고의 공급 검토 보류·변경도 공급정보 탭에서 처리한다. */
export function fieldSection(resource: QualityItem['resource'], field: string): string {
  return resource === 'ANNOUNCEMENT' ? announcementFieldSections[field] ?? 'verification' : 'verification'
}

export function landingSection(item: QualityItem): string {
  const sections = item.missingFields.map(field => fieldSection(item.resource, field))
  if (item.resource === 'ANNOUNCEMENT' && item.reasons.some(reason => reason === 'ON_HOLD' || reason === 'STALE')) sections.push('relations')
  if (item.sourceChanges.includes('SUPPLY_SOURCE')) sections.push('relations')
  if (item.sourceChanges.includes('COMPARISON_CHECK')) sections.push('verification')
  return sectionOrder.find(section => sections.includes(section)) ?? 'verification'
}
