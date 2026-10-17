import { requestManagementApi } from '../management/api'
import { isRecord, parseManagementDetail, type ManagementDetailData, type ManagementResource, type ManagementValues } from '../management/managementContract'
import { isSourceCategory, type SourceCategory } from '../ingest/sourceApi'

export type QualitySource = { category: SourceCategory; sourceKey: string; automatic: boolean; evidenceNote: string;
  rows: { id: number; name: string; collectedAt: string | null; raw: ManagementValues }[] }
export type QualityComparison = { snapshotToken: string; target: string; detail: ManagementDetailData; sources: QualitySource[] }
export function qualityComparisonPath(resource: ManagementResource, id: string) {
  return `/api/admin/quality/${resource === 'complexes' ? 'COMPLEX' : 'ANNOUNCEMENT'}/${encodeURIComponent(id)}/comparison`
}
export function parseQualityComparison(value: unknown): QualityComparison {
  if (!isRecord(value) || typeof value.snapshotToken !== 'string' || !/^[a-f0-9]{64}$/.test(value.snapshotToken)
    || typeof value.target !== 'string' || !Array.isArray(value.sources)) throw new Error('비교 응답을 확인해 주세요.')
  const sources = value.sources.map(source => {
    if (!isRecord(source) || typeof source.category !== 'string' || !isSourceCategory(source.category)
      || typeof source.sourceKey !== 'string' || typeof source.automatic !== 'boolean'
      || typeof source.evidenceNote !== 'string' || !Array.isArray(source.rows)) throw new Error('원천 응답을 확인해 주세요.')
    for (const row of source.rows) {
      if (!isRecord(row) || !Number.isSafeInteger(row.id) || typeof row.name !== 'string' || !isRecord(row.raw)
        || !(row.collectedAt === null || typeof row.collectedAt === 'string' && Number.isFinite(Date.parse(row.collectedAt)))) throw new Error('원천 행을 확인해 주세요.')
    }
    return source as QualitySource
  })
  return { snapshotToken: value.snapshotToken, target: value.target, detail: parseManagementDetail(value.detail), sources }
}
export async function loadQualityComparison(resource: ManagementResource, id: string, target: string, signal: AbortSignal) {
  return parseQualityComparison(await requestManagementApi(`${qualityComparisonPath(resource, id)}?${new URLSearchParams({ target })}`, 'GET', undefined, signal))
}
