import { getManagementDetail, managementResourcePath, requestManagementApi } from '../management/api'
import { parseManagementDetail } from '../management/managementContract'
import type { ExtractedRow } from './extraction'
import { createdTypeName, pdfTypes } from './subtypes'

/**
 * 공고문 행의 주택타입·면적(세부 타입 묶음이면 세부 타입마다)으로 단지에 없는 주택타입을 차례로 만든다. 한 면적에 주택타입이 여럿이면 첫 표기를 이름으로 쓴다. 단지 버전이 주택형마다 바뀌므로 응답의 버전을 이어 쓴다.
 * 공고문 값으로 만든 주택형이라 관리자 수정으로 보호하지 않게 표시해 다음 공고가 갱신하게 한다.
 */
export async function createPdfHousingTypes(complexId: number, rows: ExtractedRow[], existing: { name: string; exclusiveArea: number }[], signal: AbortSignal) {
  const names = new Set(existing.map(type => type.name.trim()))
  const areas = new Set(existing.map(type => type.exclusiveArea))
  let version = Number((await getManagementDetail('complexes', String(complexId), signal)).data.version)
  for (const type of rows.flatMap(pdfTypes)) {
    const name = createdTypeName(type.name)
    if (!name || names.has(name) || type.exclusiveArea === null || areas.has(type.exclusiveArea)) continue
    const result = await requestManagementApi(`${managementResourcePath('complexes')}/${complexId}/housing-types`, 'POST', {
      version, name, exclusiveArea: type.exclusiveArea, supplyArea: type.supplyArea, householdCount: null, fromAnnouncement: true }, signal)
    version = Number(parseManagementDetail(result).data.version); names.add(name); areas.add(type.exclusiveArea)
  }
}
