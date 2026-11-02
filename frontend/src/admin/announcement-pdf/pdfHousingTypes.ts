import { getManagementDetail, managementResourcePath, requestManagementApi } from '../management/api'
import { parseManagementDetail } from '../management/managementContract'
import type { ExtractedRow } from './extraction'

/**
 * 공고문 행의 형명·면적으로 단지에 없는 주택형을 차례로 만든다. 단지 버전이 주택형마다 바뀌므로 응답의 버전을 이어 쓴다.
 * 공고문 값으로 만든 주택형이라 관리자 수정으로 보호하지 않게 표시해 다음 공고가 갱신하게 한다.
 */
export async function createPdfHousingTypes(complexId: number, rows: ExtractedRow[], existing: string[], signal: AbortSignal) {
  const names = new Set(existing.map(name => name.trim()))
  let version = Number((await getManagementDetail('complexes', String(complexId), signal)).data.version)
  for (const row of rows) {
    const name = row.typeName?.trim()
    if (!name || names.has(name) || typeof row.values.exclusiveArea !== 'number') continue
    const result = await requestManagementApi(`${managementResourcePath('complexes')}/${complexId}/housing-types`, 'POST', {
      version, name, exclusiveArea: row.values.exclusiveArea, supplyArea: typeof row.values.supplyArea === 'number' ? row.values.supplyArea : null, householdCount: null, fromAnnouncement: true }, signal)
    version = Number(parseManagementDetail(result).data.version); names.add(name)
  }
}
