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

export type LhSupplyLinkView = { token: string; announcement?: LhLinkAnnouncement; myhomeRows: LhLinkMyHomeRow[]
  lhRows: LhLinkSupplyRow[]; failures?: LhSupplyMatchFailure[] }
export type LhLinkAnnouncement = { identifier: string; name: string | null; sourceUrl: string | null; panId: string | null }
/** 현재 원천과 등록 공급행으로 LH 보강 매칭을 다시 해 본 결과다. */
export type LhSupplyMatchFailure = { lhRowIdentifier: string; reason: string; detail: string; candidates: LhMatchCandidate[] }
export type LhMatchCandidate = { supplyRowId: number; myhomeRowIdentifier: string | null; sourceComplexName: string
  sourceHousingTypeName: string; complexName: string | null; housingTypeName: string | null; lhRowIdentifier: string | null }
export type LhLinkMyHomeRow = { rowIdentifier: string; complexName: string | null; housingTypeName: string | null
  supplyHouseholdCount: number | null; splitCorrected: boolean }
export type LhLinkSupplyRow = { rowIdentifier: string; complexLabel: string | null; typeName: string | null
  exclusiveArea: string | null; supplyArea: string | null; suppliedUnitCount: string | null
  linkedRowIdentifier: string | null; savedLink: SavedLhSupplyLink | null }
export type SavedLhSupplyLink = { myhomeRowIdentifier: string; sourceUrl: string; reason: string; actor: string
  updatedAt: string; reviewRequired: boolean }

function nullableText(value: unknown): value is string | null { return value === null || typeof value === 'string' }

export async function getLhSupplyLinks(identifier: string, signal?: AbortSignal): Promise<LhSupplyLinkView> {
  return parseLhSupplyLinks(await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/lh-links`, 'GET',
    undefined, signal))
}

export async function saveLhSupplyLinks(identifier: string, view: LhSupplyLinkView, myhomeRowIdentifier: string,
  lhRowIdentifiers: string[], sourceUrl: string, reason: string): Promise<LhSupplyLinkView> {
  return parseLhSupplyLinks(await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/lh-links`, 'POST',
    { token: view.token, myhomeRowIdentifier, lhRowIdentifiers, sourceUrl, reason }))
}

export async function releaseLhSupplyLink(identifier: string, view: LhSupplyLinkView, lhRowIdentifier: string,
  reason: string): Promise<LhSupplyLinkView> {
  return parseLhSupplyLinks(await requestManagementApi(`${root}/${encodeURIComponent(identifier)}/lh-links/release`,
    'POST', { token: view.token, lhRowIdentifier, reason }))
}

function parseLhSupplyLinks(value: unknown): LhSupplyLinkView {
  const invalid = () => new Error('LH 공급 연결 응답이 올바르지 않습니다.')
  if (!isRecord(value) || typeof value.token !== 'string' || !Array.isArray(value.myhomeRows)
    || !Array.isArray(value.lhRows)) throw invalid()
  const myhomeRows = value.myhomeRows.map(row => {
    if (!isRecord(row) || typeof row.rowIdentifier !== 'string' || !nullableText(row.complexName)
      || !nullableText(row.housingTypeName) || typeof row.splitCorrected !== 'boolean') throw invalid()
    return { rowIdentifier: row.rowIdentifier, complexName: row.complexName, housingTypeName: row.housingTypeName,
      supplyHouseholdCount: parseCount(row.supplyHouseholdCount), splitCorrected: row.splitCorrected }
  })
  const lhRows = value.lhRows.map(row => {
    if (!isRecord(row) || typeof row.rowIdentifier !== 'string' || !nullableText(row.complexLabel)
      || !nullableText(row.typeName) || !nullableText(row.exclusiveArea) || !nullableText(row.supplyArea)
      || !nullableText(row.suppliedUnitCount) || !nullableText(row.linkedRowIdentifier)) throw invalid()
    return { rowIdentifier: row.rowIdentifier, complexLabel: row.complexLabel, typeName: row.typeName,
      exclusiveArea: row.exclusiveArea, supplyArea: row.supplyArea, suppliedUnitCount: row.suppliedUnitCount,
      linkedRowIdentifier: row.linkedRowIdentifier, savedLink: parseSavedLhLink(row.savedLink, invalid) }
  })
  return { token: value.token, myhomeRows, lhRows,
    ...(value.announcement !== undefined ? { announcement: parseLhAnnouncement(value.announcement, invalid) } : {}),
    ...(value.failures !== undefined ? { failures: parseLhFailures(value.failures, invalid) } : {}) }
}

function parseLhAnnouncement(value: unknown, invalid: () => Error): LhLinkAnnouncement {
  if (!isRecord(value) || typeof value.identifier !== 'string' || !nullableText(value.name)
    || !nullableText(value.sourceUrl) || !nullableText(value.panId)) throw invalid()
  const sourceUrl = value.sourceUrl !== null && /^https?:\/\//i.test(value.sourceUrl) ? value.sourceUrl : null
  return { identifier: value.identifier, name: value.name, sourceUrl, panId: value.panId }
}

function parseLhFailures(value: unknown, invalid: () => Error): LhSupplyMatchFailure[] {
  if (!Array.isArray(value)) throw invalid()
  return value.map(failure => {
    if (!isRecord(failure) || typeof failure.lhRowIdentifier !== 'string' || typeof failure.reason !== 'string'
      || typeof failure.detail !== 'string' || !Array.isArray(failure.candidates)) throw invalid()
    const candidates = failure.candidates.map(candidate => {
      if (!isRecord(candidate) || !id(candidate.supplyRowId) || !nullableText(candidate.myhomeRowIdentifier)
        || typeof candidate.sourceComplexName !== 'string' || typeof candidate.sourceHousingTypeName !== 'string'
        || !nullableText(candidate.complexName) || !nullableText(candidate.housingTypeName)
        || !nullableText(candidate.lhRowIdentifier)) throw invalid()
      return { supplyRowId: candidate.supplyRowId, myhomeRowIdentifier: candidate.myhomeRowIdentifier,
        sourceComplexName: candidate.sourceComplexName, sourceHousingTypeName: candidate.sourceHousingTypeName,
        complexName: candidate.complexName, housingTypeName: candidate.housingTypeName,
        lhRowIdentifier: candidate.lhRowIdentifier }
    })
    return { lhRowIdentifier: failure.lhRowIdentifier, reason: failure.reason, detail: failure.detail, candidates }
  })
}

function parseSavedLhLink(value: unknown, invalid: () => Error): SavedLhSupplyLink | null {
  if (value === null) return null
  if (!isRecord(value) || typeof value.myhomeRowIdentifier !== 'string' || typeof value.sourceUrl !== 'string'
    || !/^https?:\/\//i.test(value.sourceUrl) || typeof value.reason !== 'string' || typeof value.actor !== 'string'
    || typeof value.updatedAt !== 'string' || typeof value.reviewRequired !== 'boolean') throw invalid()
  return { myhomeRowIdentifier: value.myhomeRowIdentifier, sourceUrl: value.sourceUrl, reason: value.reason,
    actor: value.actor, updatedAt: value.updatedAt, reviewRequired: value.reviewRequired }
}
