import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 세대수 보강 실패의 LH 원천과, 공급유형·전체 세대수·지역이 같은 단지 후보를 현재 데이터로 비교한 결과다. */
export type HouseholdComplexLinkView = { failureId: number; token: string; source: HouseholdSource
  candidates: HouseholdCandidate[]; savedLink: HouseholdSavedLink | null; linkable: boolean }
export type HouseholdSource = { areaName: string | null; supplyTypeName: string | null; complexName: string | null
  totalHouseholdCount: number | null; housingTypes: { exclusiveArea: number; householdCount: number }[]
  invalidReason: string | null }
export type HouseholdCandidate = { id: number; name: string; supplyType: string; totalHouseholdCount: number
  roadAddress: string; exclusiveAreas: number[] }
export type HouseholdSavedLink = { complexId: number | null; complexName: string | null; sourceUrl: string | null
  reason: string; actor: string; updatedAt: string; reviewRequired: boolean }

const root = '/api/admin/ingest/lh/housing-type-households/failures'

export async function getHouseholdComplexLink(failureId: number, signal?: AbortSignal): Promise<HouseholdComplexLinkView> {
  return parse(await requestManagementApi(`${root}/${failureId}/complex-link`, 'GET', undefined, signal))
}

export async function saveHouseholdComplexLink(view: HouseholdComplexLinkView, complexId: number, reason: string,
  sourceUrl: string): Promise<HouseholdComplexLinkView> {
  return parse(await requestManagementApi(`${root}/${view.failureId}/complex-link`, 'POST',
    { complexId, token: view.token, reason, ...(sourceUrl ? { sourceUrl } : {}) }))
}

export async function releaseHouseholdComplexLink(view: HouseholdComplexLinkView, reason: string): Promise<HouseholdComplexLinkView> {
  return parse(await requestManagementApi(`${root}/${view.failureId}/complex-link/release`, 'POST',
    { token: view.token, reason }))
}

function text(value: unknown): value is string | null { return value === null || typeof value === 'string' }
function count(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 }
function decimal(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) }

function parse(value: unknown): HouseholdComplexLinkView {
  const invalid = () => new Error('세대수 보강 비교 응답이 올바르지 않습니다.')
  if (!isRecord(value) || !count(value.failureId) || typeof value.token !== 'string' || !isRecord(value.source)
    || !Array.isArray(value.candidates) || typeof value.linkable !== 'boolean') throw invalid()
  const source = value.source
  if (!text(source.areaName) || !text(source.supplyTypeName) || !text(source.complexName)
    || !(source.totalHouseholdCount === null || count(source.totalHouseholdCount)) || !Array.isArray(source.housingTypes)
    || !text(source.invalidReason)) throw invalid()
  const housingTypes = source.housingTypes.map(type => {
    if (!isRecord(type) || !decimal(type.exclusiveArea) || !count(type.householdCount)) throw invalid()
    return { exclusiveArea: type.exclusiveArea, householdCount: type.householdCount }
  })
  const candidates = value.candidates.map(candidate => {
    if (!isRecord(candidate) || !count(candidate.id) || typeof candidate.name !== 'string'
      || typeof candidate.supplyType !== 'string' || !count(candidate.totalHouseholdCount)
      || typeof candidate.roadAddress !== 'string' || !Array.isArray(candidate.exclusiveAreas)
      || !candidate.exclusiveAreas.every(decimal)) throw invalid()
    return { id: candidate.id, name: candidate.name, supplyType: candidate.supplyType,
      totalHouseholdCount: candidate.totalHouseholdCount, roadAddress: candidate.roadAddress,
      exclusiveAreas: candidate.exclusiveAreas as number[] }
  })
  return { failureId: value.failureId, token: value.token, linkable: value.linkable, candidates,
    source: { areaName: source.areaName, supplyTypeName: source.supplyTypeName, complexName: source.complexName,
      totalHouseholdCount: source.totalHouseholdCount, housingTypes, invalidReason: source.invalidReason },
    savedLink: savedLink(value.savedLink, invalid) }
}

function savedLink(value: unknown, invalid: () => Error): HouseholdSavedLink | null {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || !(value.complexId === null || count(value.complexId)) || !text(value.complexName)
    || !text(value.sourceUrl) || typeof value.reason !== 'string' || typeof value.actor !== 'string'
    || typeof value.updatedAt !== 'string' || typeof value.reviewRequired !== 'boolean') throw invalid()
  const sourceUrl = value.sourceUrl !== null && /^https?:\/\//i.test(value.sourceUrl) ? value.sourceUrl : null
  return { complexId: value.complexId, complexName: value.complexName, sourceUrl, reason: value.reason,
    actor: value.actor, updatedAt: value.updatedAt, reviewRequired: value.reviewRequired }
}
