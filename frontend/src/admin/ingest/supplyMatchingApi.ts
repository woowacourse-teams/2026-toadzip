import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type SupplyMatch = {
  rowIdentifier: string; token: string; sourceComplexName: string; sourceHousingTypeName: string
  pnu: string; exclusiveArea: number | null; supplyArea: number | null
  complexId: number | null; housingTypeId: number | null; failure: string | null
  savedMatch?: SavedSupplyMatch | null
  totalSupplyHouseholdCount?: number | null
  splitGroup?: SplitSupplyGroup | null
}
export type SplitSupplyGroup = { id: number; sharedHouseholdCount: number | null; sourceUrl: string; reason: string
  actor: string; updatedAt: string; reviewRequired: boolean
  rows: { id: number; name: string; complexId: number | null; housingTypeId: number | null }[] }
export type SplitSupplySelection = { id?: number; name: string; complexId: number; housingTypeId: number; targetToken: string }
export type SavedSupplyMatch = { complexId: number | null; housingTypeId: number | null; sourceUrl: string
  reason: string; actor: string; updatedAt: string; reviewRequired: boolean }
export type MatchEvidence = { remember: true; sourceUrl: string; reason: string }
export type SupplySelection = { rowIdentifier: string; token: string; complexId: number; housingTypeId: number | null; targetToken?: string }
export type MatchingComplex = { id: number; name: string; roadAddress: string; supplyType: string }
export type MatchingHousingType = { id: number; name: string; exclusiveArea: number; supplyArea: number | null; householdCount?: number | null; token?: string }
const root = '/api/admin/ingest/announcement-supply-matches'
function number(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) }
function id(value: unknown): value is number { return number(value) && Number.isSafeInteger(value) && value > 0 }
function nullableNumber(value: unknown): value is number | null { return value === null || number(value) }
function nullableId(value: unknown): value is number | null { return value === null || id(value) }

export async function getSupplyMatches(identifier: string, signal?: AbortSignal): Promise<SupplyMatch[]> {
  const result = await requestManagementApi(`${root}/${encodeURIComponent(identifier)}`, 'GET', undefined, signal)
  return parseMatches(result)
}

function parseMatches(result: unknown): SupplyMatch[] {
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
      failure: value.failure, ...(value.savedMatch !== undefined ? { savedMatch: parseSavedMatch(value.savedMatch) } : {}),
      ...(value.totalSupplyHouseholdCount !== undefined ? { totalSupplyHouseholdCount: parseCount(value.totalSupplyHouseholdCount) } : {}),
      ...(value.splitGroup !== undefined ? { splitGroup: parseSplitGroup(value.splitGroup) } : {}) }
  })
}

function parseCount(value: unknown): number | null {
  if (value === null) return null
  if (!number(value) || !Number.isSafeInteger(value) || value < 0) throw new Error('모집수 응답이 올바르지 않습니다.')
  return value
}

function parseSplitGroup(value: unknown): SplitSupplyGroup | null {
  if (value === null) return null
  if (!isRecord(value) || !id(value.id) || typeof value.sourceUrl !== 'string' || !/^https?:\/\//i.test(value.sourceUrl)
    || typeof value.reason !== 'string' || typeof value.actor !== 'string' || typeof value.updatedAt !== 'string'
    || typeof value.reviewRequired !== 'boolean' || !Array.isArray(value.rows)) throw new Error('보정 공급행 응답이 올바르지 않습니다.')
  const rows = value.rows.map(row => {
    if (!isRecord(row) || !id(row.id) || typeof row.name !== 'string' || !nullableId(row.complexId) || !nullableId(row.housingTypeId)) {
      throw new Error('보정 공급행 응답이 올바르지 않습니다.')
    }
    return { id: row.id, name: row.name, complexId: row.complexId, housingTypeId: row.housingTypeId }
  })
  return { id: value.id, sharedHouseholdCount: parseCount(value.sharedHouseholdCount), sourceUrl: value.sourceUrl,
    reason: value.reason, actor: value.actor, updatedAt: value.updatedAt, reviewRequired: value.reviewRequired, rows }
}

export async function splitSupplyRow(identifier: string, row: SupplyMatch, rows: SplitSupplySelection[],
  sourceUrl: string, reason: string): Promise<SupplyMatch[]> {
  return parseMatches(await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/split`, 'POST',
    { rowIdentifier: row.rowIdentifier, token: row.token, rows, sourceUrl, reason }))
}

function parseSavedMatch(value: unknown): SavedSupplyMatch | null {
  if (value === null) return null
  if (!isRecord(value) || !nullableId(value.complexId) || !nullableId(value.housingTypeId)
    || typeof value.sourceUrl !== 'string' || !/^https?:\/\//i.test(value.sourceUrl)
    || typeof value.reason !== 'string' || typeof value.actor !== 'string' || typeof value.updatedAt !== 'string'
    || typeof value.reviewRequired !== 'boolean') throw new Error('저장한 매칭 응답이 올바르지 않습니다.')
  return { complexId: value.complexId, housingTypeId: value.housingTypeId, sourceUrl: value.sourceUrl,
    reason: value.reason, actor: value.actor, updatedAt: value.updatedAt, reviewRequired: value.reviewRequired }
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
    if (value.token !== undefined && typeof value.token !== 'string') throw new Error('주택형 응답이 올바르지 않습니다.')
    if (value.householdCount !== undefined && !nullableNumber(value.householdCount)) throw new Error('주택형 응답이 올바르지 않습니다.')
    return { id: value.id, name: value.name, exclusiveArea: value.exclusiveArea, supplyArea: value.supplyArea,
      ...(value.token !== undefined ? { token: value.token } : {}),
      ...(value.householdCount !== undefined ? { householdCount: value.householdCount } : {}) }
  })
}

export async function refineSupplyMatches(identifier: string, rows: SupplySelection[], evidence?: MatchEvidence): Promise<void> {
  const result = await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/refine`, 'POST', { rows, ...evidence })
  if (!isRecord(result) || !Number.isSafeInteger(result.createdAnnouncementCount)
    || !Number.isSafeInteger(result.updatedAnnouncementCount)
    || result.failedSourceRowCount !== 0) throw new Error('정제 결과 응답이 올바르지 않습니다. 공고 관리에서 결과를 확인해 주세요.')
}

/** 선택 없이 저장한 매칭·자동 매칭으로 이 공고만 다시 정제한다. 하나라도 실패하면 서버가 전체를 되돌린다. */
export async function rerunAnnouncement(identifier: string): Promise<void> {
  const result = await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/rerun`, 'POST')
  if (!isRecord(result) || !Number.isSafeInteger(result.updatedAnnouncementCount)
    || result.failedSourceRowCount !== 0) throw new Error('정제 결과 응답이 올바르지 않습니다. 오류 목록에서 결과를 확인해 주세요.')
}

export async function releaseSupplyMatch(identifier: string, row: SupplyMatch, reason: string): Promise<SupplyMatch[]> {
  return parseMatches(await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/release`, 'POST',
    { rowIdentifier: row.rowIdentifier, token: row.token, reason }))
}
