import { getManagementDetail, managementResourcePath, requestManagementApi } from '../management/api'
import { parseManagementDetail } from '../management/managementContract'
import type { ExtractedRow } from './extraction'
import { announcementHousingType, sameTypeName } from './areas'
import { createdTypeName, pdfTypes } from './subtypes'

/**
 * 공고문 행의 주택타입 이름·면적(세부 타입 묶음이면 세부 타입마다)으로 단지에 없는 주택타입을 차례로 만든다. 단지 주택타입은 공고문 표기
 * 이름으로 나누므로 같은 면적이어도 이름이 다르면(55C-1·55D-1) 따로 만든다. 한 세부 타입에 이름이 여럿('59A·59A1')이면 첫 표기를 쓴다.
 * 단지 버전이 주택형마다 바뀌므로 응답의 버전을 이어 쓴다. 공고문 값으로 만든 주택형이라 관리자 수정으로 보호하지 않게 표시해 다음 공고가 갱신하게 한다.
 */
export async function createPdfHousingTypes(complexId: number, rows: ExtractedRow[], existing: { name: string; exclusiveArea: number }[], signal: AbortSignal) {
  // 저장할 때 잇는 기준(announcementHousingType)과 같다. 이름 형식만 다른 같은 집(기존 '26' ↔ 공고문 '26A')은 만들지 않는다.
  const known = existing.map((type, at) => ({ id: at, name: type.name, exclusiveArea: type.exclusiveArea, supplyArea: null }))
  const pdf = rows.flatMap(pdfTypes).map(type => ({ ...type, name: createdTypeName(type.name) }))
  let version = Number((await getManagementDetail('complexes', String(complexId), signal)).data.version)
  for (const type of pdf) {
    const name = type.name, others = pdf.filter(other => other.name && !sameTypeName(other.name, name, other.exclusiveArea, type.exclusiveArea))
    if (!name || type.exclusiveArea === null || announcementHousingType(known, name, type.exclusiveArea, others)) continue
    const result = await requestManagementApi(`${managementResourcePath('complexes')}/${complexId}/housing-types`, 'POST', {
      version, name, exclusiveArea: type.exclusiveArea, supplyArea: type.supplyArea, householdCount: null, fromAnnouncement: true }, signal)
    version = Number(parseManagementDetail(result).data.version); known.push({ id: known.length, name, exclusiveArea: type.exclusiveArea, supplyArea: null })
  }
}
