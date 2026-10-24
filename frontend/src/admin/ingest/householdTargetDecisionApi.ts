import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 같은 단지에 매칭된 LH 임대 목록 묶음들과 그 단지의 주택형을 현재 데이터로 비교한 결과다. */
export type HouseholdTargetDecisionView = { failureId: number; token: string; complex: DecisionComplex | null
  groups: DecisionSourceGroup[]; identical: boolean; decidable: boolean; decision: SavedTargetDecision | null }
export type DecisionComplex = { id: number; name: string; supplyType: string; totalHouseholdCount: number
  roadAddress: string; housingTypes: { id: number; name: string; exclusiveArea: number; totalHouseholdCount: number | null }[] }
export type DecisionSourceGroup = { sourceKey: string; complexName: string; supplyTypeName: string; areaName: string
  totalHouseholdCount: number; housingTypes: { exclusiveArea: number; householdCount: number }[]; failureSource: boolean }
export type SavedTargetDecision = { decision: 'USE_SOURCE' | 'SKIP'; chosenSourceKey: string | null; reason: string
  actor: string; updatedAt: string; reviewRequired: boolean }
export type TargetDecisionChoice = { decision: 'USE_SOURCE'; chosenSourceKey: string } | { decision: 'SKIP' }

const root = '/api/admin/ingest/lh/housing-type-households/failures'

export async function getHouseholdTargetDecision(failureId: number, signal?: AbortSignal): Promise<HouseholdTargetDecisionView> {
  return parse(await requestManagementApi(`${root}/${failureId}/target-decision`, 'GET', undefined, signal))
}

export async function saveHouseholdTargetDecision(view: HouseholdTargetDecisionView, choice: TargetDecisionChoice,
  reason: string): Promise<HouseholdTargetDecisionView> {
  return parse(await requestManagementApi(`${root}/${view.failureId}/target-decision`, 'POST',
    { ...choice, token: view.token, reason }))
}

export async function releaseHouseholdTargetDecision(view: HouseholdTargetDecisionView, reason: string): Promise<HouseholdTargetDecisionView> {
  return parse(await requestManagementApi(`${root}/${view.failureId}/target-decision/release`, 'POST',
    { token: view.token, reason }))
}

function count(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 }
function decimal(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) }

function parse(value: unknown): HouseholdTargetDecisionView {
  const invalid = () => new Error('세대수 보강 단지 결정 응답이 올바르지 않습니다.')
  if (!isRecord(value) || !count(value.failureId) || typeof value.token !== 'string' || !Array.isArray(value.groups)
    || typeof value.identical !== 'boolean' || typeof value.decidable !== 'boolean') throw invalid()
  const groups = value.groups.map(group => {
    if (!isRecord(group) || typeof group.sourceKey !== 'string' || typeof group.complexName !== 'string'
      || typeof group.supplyTypeName !== 'string' || typeof group.areaName !== 'string'
      || !count(group.totalHouseholdCount) || !Array.isArray(group.housingTypes)
      || typeof group.failureSource !== 'boolean') throw invalid()
    const housingTypes = group.housingTypes.map(type => {
      if (!isRecord(type) || !decimal(type.exclusiveArea) || !count(type.householdCount)) throw invalid()
      return { exclusiveArea: type.exclusiveArea, householdCount: type.householdCount }
    })
    return { sourceKey: group.sourceKey, complexName: group.complexName, supplyTypeName: group.supplyTypeName,
      areaName: group.areaName, totalHouseholdCount: group.totalHouseholdCount, housingTypes, failureSource: group.failureSource }
  })
  return { failureId: value.failureId, token: value.token, complex: complex(value.complex, invalid), groups,
    identical: value.identical, decidable: value.decidable, decision: decision(value.decision, invalid) }
}

function complex(value: unknown, invalid: () => Error): DecisionComplex | null {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || !count(value.id) || typeof value.name !== 'string' || typeof value.supplyType !== 'string'
    || !count(value.totalHouseholdCount) || typeof value.roadAddress !== 'string' || !Array.isArray(value.housingTypes)) throw invalid()
  const housingTypes = value.housingTypes.map(type => {
    if (!isRecord(type) || !count(type.id) || typeof type.name !== 'string' || !decimal(type.exclusiveArea)
      || !(type.totalHouseholdCount === null || count(type.totalHouseholdCount))) throw invalid()
    return { id: type.id, name: type.name, exclusiveArea: type.exclusiveArea, totalHouseholdCount: type.totalHouseholdCount }
  })
  return { id: value.id, name: value.name, supplyType: value.supplyType, totalHouseholdCount: value.totalHouseholdCount,
    roadAddress: value.roadAddress, housingTypes }
}

function decision(value: unknown, invalid: () => Error): SavedTargetDecision | null {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || (value.decision !== 'USE_SOURCE' && value.decision !== 'SKIP')
    || !(value.chosenSourceKey === null || typeof value.chosenSourceKey === 'string') || typeof value.reason !== 'string'
    || typeof value.actor !== 'string' || typeof value.updatedAt !== 'string' || typeof value.reviewRequired !== 'boolean') throw invalid()
  return { decision: value.decision, chosenSourceKey: value.chosenSourceKey, reason: value.reason, actor: value.actor,
    updatedAt: value.updatedAt, reviewRequired: value.reviewRequired }
}
