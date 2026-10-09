import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type SupplyMatch = {
  rowIdentifier: string; token: string; sourceComplexName: string; sourceHousingTypeName: string
  pnu: string; exclusiveArea: number | null; supplyArea: number | null
  complexId: number | null; housingTypeId: number | null; failure: string | null
}
export type SupplySelection = { rowIdentifier: string; token: string; complexId: number; housingTypeId: number | null }
export type MatchingComplex = { id: number; name: string; roadAddress: string; supplyType: string }
export type MatchingHousingType = { id: number; name: string; exclusiveArea: number; supplyArea: number | null }
const root = '/api/admin/ingest/announcement-supply-matches'
function number(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) }
function id(value: unknown): value is number { return number(value) && Number.isSafeInteger(value) && value > 0 }
function nullableNumber(value: unknown): value is number | null { return value === null || number(value) }
function nullableId(value: unknown): value is number | null { return value === null || id(value) }

export async function getSupplyMatches(identifier: string, signal?: AbortSignal): Promise<SupplyMatch[]> {
  const result = await requestManagementApi(`${root}/${encodeURIComponent(identifier)}`, 'GET', undefined, signal)
  if (!Array.isArray(result)) throw new Error('매칭 정보 응답이 올바르지 않습니다.')
  return result.map(value => {
    if (!isRecord(value) || typeof value.rowIdentifier !== 'string' || typeof value.token !== 'string'
      || typeof value.sourceComplexName !== 'string' || typeof value.sourceHousingTypeName !== 'string'
      || typeof value.pnu !== 'string' || !nullableNumber(value.exclusiveArea) || !nullableNumber(value.supplyArea)
      || !nullableId(value.complexId) || !nullableId(value.housingTypeId)
      || !(value.failure === null || typeof value.failure === 'string')) throw new Error('매칭 정보 응답이 올바르지 않습니다.')
    return { rowIdentifier: value.rowIdentifier, token: value.token, sourceComplexName: value.sourceComplexName,
      sourceHousingTypeName: value.sourceHousingTypeName, pnu: value.pnu, exclusiveArea: value.exclusiveArea,
      supplyArea: value.supplyArea, complexId: value.complexId, housingTypeId: value.housingTypeId,
      failure: value.failure }
  })
}

export async function searchMatchingComplexes(query: string, signal?: AbortSignal): Promise<MatchingComplex[]> {
  const result = await requestManagementApi(`${root}/complexes?${new URLSearchParams({ query })}`, 'GET', undefined, signal)
  if (!Array.isArray(result)) throw new Error('단지 검색 응답이 올바르지 않습니다.')
  return result.map(value => {
    if (!isRecord(value) || !id(value.id) || typeof value.name !== 'string' || typeof value.roadAddress !== 'string'
      || typeof value.supplyType !== 'string') throw new Error('단지 검색 응답이 올바르지 않습니다.')
    return { id: value.id, name: value.name, roadAddress: value.roadAddress, supplyType: value.supplyType }
  })
}

export async function getMatchingHousingTypes(complexId: number, signal?: AbortSignal): Promise<MatchingHousingType[]> {
  const result = await requestManagementApi(`${root}/complexes/${complexId}/housing-types`, 'GET', undefined, signal)
  if (!Array.isArray(result)) throw new Error('주택형 응답이 올바르지 않습니다.')
  return result.map(value => {
    if (!isRecord(value) || !id(value.id) || typeof value.name !== 'string' || !number(value.exclusiveArea)
      || !nullableNumber(value.supplyArea)) throw new Error('주택형 응답이 올바르지 않습니다.')
    return { id: value.id, name: value.name, exclusiveArea: value.exclusiveArea, supplyArea: value.supplyArea }
  })
}

export async function refineSupplyMatches(identifier: string, rows: SupplySelection[]): Promise<void> {
  const result = await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/refine`, 'POST', { rows })
  if (!isRecord(result) || !Number.isSafeInteger(result.createdAnnouncementCount)
    || !Number.isSafeInteger(result.updatedAnnouncementCount)
    || result.failedSourceRowCount !== 0) throw new Error('정제 결과 응답이 올바르지 않습니다. 공고 관리에서 결과를 확인해 주세요.')
}
