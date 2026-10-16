import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export const qualityReasons: Record<string, string> = {
  MISSING: '정보 누락', SOURCE_MISMATCH: '수집값 불일치', STALE: '확인값 변경',
  UNREVIEWED: '아직 미검토', ON_HOLD: '검토 보류', SOURCE_CHANGED: '원천 변경·소실',
}
export type QualityItem = { resource: 'COMPLEX' | 'ANNOUNCEMENT'; id: number; name: string;
  reasons: string[]; missingFields: string[]; updatedAt: string | null }
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
      || !(item.updatedAt === null || typeof item.updatedAt === 'string' && Number.isFinite(Date.parse(item.updatedAt)))) {
      throw new Error('검토 항목 응답을 확인해 주세요.')
    }
  }
  return result as QualityPage
}
