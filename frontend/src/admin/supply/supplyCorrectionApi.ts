import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type CorrectionTarget = {
  id: number | null; key: string; excluded: boolean; protectedValues: boolean; displayOrder: number;
  target: string; supplyRank: string | null; householdCount: number | null; reserveCount: number | null;
  deposit: string | null; rent: string | null; convertedDeposit: string | null; condition: string | null;
  /** 같은 공급행의 다른 대상과 함께 쓰는 모집수·예비자 수(공통 모집수). 묶인 동안 그 종류의 대상 칸은 비운다. */
  householdCountGroup?: TargetCountGroup | null; reserveCountGroup?: TargetCountGroup | null;
}
/** 공통 모집수 하나. 같은 key의 대상끼리 한 묶음이고 저장된 묶음은 "group:{id}"다. */
export type TargetCountGroup = { key: string; count: number }
export type NewHousingType = { name: string; exclusiveArea: number; supplyArea: number | null }
/** 공급행이 대표 주택형과 함께 모집하는 주택형 하나. 같은 단지의 기존 주택형이나 이 저장에서 만들 새 주택형 중 하나다. */
export type LinkedHousingType = { housingTypeId: number; newHousingType?: null } | { housingTypeId?: null; newHousingType: NewHousingType }
/**
 * linkedHousingTypes는 대표(housingTypeId·newHousingType) 밖의 함께 모집하는 주택형(세부 타입 26B·26C)이다. 조회는 지금 연결을 주고,
 * 요청에서 생략하면 대표가 그대로일 때 지금 연결을 유지하고 대표가 바뀌면 대표만 남긴다. 빈 목록은 대표만 남긴다.
 * 대표나 단지를 바꾸는 화면은 빈 목록을 보낸다. 생략하면 대표를 원래대로 되돌렸을 때 이전 연결이 남는다.
 */
export type CorrectionRow = {
  id: number | null; key: string; housingComplexId: number | null; housingComplexName: string | null;
  housingTypeId: number | null; newHousingType: NewHousingType | null; linkedHousingTypes?: LinkedHousingType[];
  groupId: number | null; sharedHouseholdCount: number | null; protectedValues: boolean; excluded: boolean;
  displayOrder: number; complexName: string; typeName: string; pnu: string; moveInMonth: string | null;
  category: 'NEW_SUPPLY' | 'RESUPPLY'; householdCount: number | null; areaOverride: boolean;
  exclusiveArea: number | null; supplyArea: number | null; targets: CorrectionTarget[]; targetCountsComparable: boolean;
}
export type CorrectionSource = { category: string; sourceKey: string; rows: { collectedAt?: string | null; raw: unknown }[] }
/** 공급행이 기록한 원천행. sources에서 같은 분류·키의 원천을 찾는다. */
export type RowSource = { rowKey: string; category: string; sourceKey: string }
/**
 * LH 공급 원본 한 행(`LH:{panId}:SUPPLY:{n}`). LH 행은 공급행을 정하지 않는 보조 원천으로 세대수 검산과 식별에만 쓴다.
 * householdCount는 LH 이번 공급 수(정수가 아니면 null), attachedRowKey는 같은 공고 단지에서 전용면적(소수 둘째 자리)이 같아
 * 서버가 이 LH 행을 붙인 공급행이다. ambiguous는 같은 면적 공급행이 여럿이라 표시 순서 첫 행에 붙였다는 뜻이다.
 */
export type LhSupplyRow = { rowIdentifier: string; complexLabel: string | null; typeName: string | null; exclusiveArea: string | null
  householdCount?: number | null; attachedRowKey?: string | null; ambiguous?: boolean }
/**
 * deletableRowKeys: 지울 수 있는 행(원천 없이 관리자가 추가한 행). 원천행은 제외만 할 수 있다.
 * lhSupplyRows: 이 공고의 최신 LH 공급 원본 행 전체. unattachedLhSupplyRows: 그중 같은 전용면적 공급행이 없어 붙지 않은 행. 오류가 아니라 정보다.
 */
/**
 * 공고 단지. 공급행의 complexName이 이 name이며, PDF 단지를 주소로 대응할 때 쓴다.
 * linkMethod는 연결 방법(AUTO 주소 규칙·ADMIN 관리자·MIGRATED 기존 공급행 이관), 연결이 없으면 null이다. rowCount는 제외하지 않은 공급행 수다.
 */
export type ComplexLinkMethod = 'AUTO' | 'ADMIN' | 'MIGRATED'
export type AnnouncementComplex = { id: number; name: string; roadAddress: string | null; housingComplexId: number | null; housingComplexName: string | null; linkMethod: ComplexLinkMethod | null; rowCount: number }
export type SupplyWorkspace = { snapshotToken: string; version: number; rows: CorrectionRow[]; sources: CorrectionSource[]; sourceWarnings?: string[]; rowSources?: RowSource[]; deletableRowKeys?: string[]; lhSupplyRows?: LhSupplyRow[]; unattachedLhSupplyRows?: LhSupplyRow[]; announcementComplexes?: AnnouncementComplex[]
  /** 행 키별 주택형(신청형) 이름(공고 표기, 없으면 원천 형명). 행 이름(typeName)은 공고 표기가 없으면 연결 주택형 이름이라 신청형 판정에 이 값을 쓴다. */
  applicationTypeNames?: Record<string, string>
  /** 행 키별 원천 전용면적(정제가 매칭용으로 적어 둔 공고·LH 면적). 주택형이 없어 행 면적이 비어도 저장할 곳을 면적으로 고르는 데 쓴다. */
  matchingAreas?: Record<string, number> }
/** 요청 행은 서버가 모르는 필드를 거절한다. 주택형을 비우고 전용면적만 준 단지 연결 행은 서버가 단지·전용면적으로 주택형을 정한다. */
export type SupplyCorrectionInput = { operationId: string; snapshotToken: string; reason: string; sourceUrl: string; sourcePage: string | null; rows: CorrectionRow[]; deletedRowKeys?: string[] }
/** after는 서버가 정한 주택형(housingTypeId 또는 newHousingType)이 담긴 저장 결과다. */
export type SupplyPreview = { confirmationToken: string; before: CorrectionRow[]; after: CorrectionRow[]; warnings: string[] }
export type SupplyApplied = { changeId: number; alreadyApplied: boolean; workspace: SupplyWorkspace }
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const nullableId = (value: unknown) => value === null || count(value) && value > 0
const nullableCount = (value: unknown) => value === null || count(value)
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const area = (value: unknown) => value === null || typeof value === 'number' && Number.isFinite(value) && value >= 0
const money = (value: unknown) => value === null || typeof value === 'string' && /^\d+$/.test(value)
const newType = (value: unknown) => isRecord(value) && typeof value.name === 'string' && typeof value.exclusiveArea === 'number' && area(value.exclusiveArea) && area(value.supplyArea)
const linkedType = (value: unknown) => isRecord(value) && (count(value.housingTypeId) && value.housingTypeId > 0 && value.newHousingType == null
  || value.housingTypeId == null && newType(value.newHousingType))
const lhRows = (value: unknown) => Array.isArray(value) && value.every(item => isRecord(item) && typeof item.rowIdentifier === 'string'
  && (item.householdCount == null || count(item.householdCount)) && (item.attachedRowKey == null || typeof item.attachedRowKey === 'string')
  && (item.ambiguous == null || typeof item.ambiguous === 'boolean'))
const countGroup = (value: unknown) => value == null || isRecord(value) && typeof value.key === 'string' && count(value.count)
function invalid(): never { throw new Error('공급정보 보정 응답이 올바르지 않습니다.') }
function target(value: unknown): CorrectionTarget {
  if (!isRecord(value) || !nullableId(value.id) || typeof value.key !== 'string'
    || typeof value.excluded !== 'boolean' || typeof value.protectedValues !== 'boolean' || !count(value.displayOrder)
    || typeof value.target !== 'string' || !nullableText(value.supplyRank) || !nullableText(value.condition)
    || !nullableCount(value.householdCount) || !nullableCount(value.reserveCount)
    || !money(value.deposit) || !money(value.rent) || !money(value.convertedDeposit)
    || !countGroup(value.householdCountGroup) || !countGroup(value.reserveCountGroup)) invalid()
  return value as CorrectionTarget
}
function row(value: unknown): CorrectionRow {
  if (!isRecord(value) || !nullableId(value.id) || typeof value.key !== 'string'
    || !nullableId(value.housingComplexId) || !nullableText(value.housingComplexName) || !nullableId(value.housingTypeId)
    || !nullableId(value.groupId) || !nullableCount(value.sharedHouseholdCount)
    || typeof value.protectedValues !== 'boolean' || typeof value.excluded !== 'boolean' || !count(value.displayOrder)
    || typeof value.complexName !== 'string' || typeof value.typeName !== 'string' || value.pnu !== null && typeof value.pnu !== 'string'
    || !nullableText(value.moveInMonth) || !['NEW_SUPPLY', 'RESUPPLY'].includes(String(value.category))
    || !nullableCount(value.householdCount) || typeof value.areaOverride !== 'boolean'
    || !area(value.exclusiveArea) || !area(value.supplyArea) || !Array.isArray(value.targets)
    || typeof value.targetCountsComparable !== 'boolean') invalid()
  if (value.newHousingType !== null && !newType(value.newHousingType)) invalid()
  if (value.linkedHousingTypes != null && (!Array.isArray(value.linkedHousingTypes) || !value.linkedHousingTypes.every(linkedType))) invalid()
  // 지번을 모르는 공급행은 PNU가 null이다. 화면은 빈 칸으로 다루고, 서버는 빈 PNU를 null로 받는다.
  // 조회 응답은 비어 있는 쪽을 null로 함께 주므로 화면이 만드는 항목과 같은 모양으로 맞춰, 뺐다가 다시 넣은 연결을 바뀐 값으로 보지 않게 한다.
  const linkedHousingTypes = (value.linkedHousingTypes as LinkedHousingType[] | null | undefined)
    ?.map(link => link.housingTypeId != null ? { housingTypeId: link.housingTypeId } : { newHousingType: link.newHousingType })
  return { ...value, pnu: value.pnu ?? '', targets: value.targets.map(target), linkedHousingTypes } as CorrectionRow
}
export function parseSupplyWorkspace(value: unknown): SupplyWorkspace {
  if (!isRecord(value) || typeof value.snapshotToken !== 'string' || !count(value.version)
    || !Array.isArray(value.rows) || !Array.isArray(value.sources)) invalid()
  for (const source of value.sources) {
    if (!isRecord(source) || typeof source.category !== 'string' || typeof source.sourceKey !== 'string'
      || !Array.isArray(source.rows) || !source.rows.every(item => isRecord(item) && 'raw' in item)) invalid()
  }
  if (value.sourceWarnings !== undefined && (!Array.isArray(value.sourceWarnings) || !value.sourceWarnings.every(item => typeof item === 'string'))) invalid()
  if (value.rowSources !== undefined && (!Array.isArray(value.rowSources) || !value.rowSources.every(item => isRecord(item)
    && typeof item.rowKey === 'string' && typeof item.category === 'string' && typeof item.sourceKey === 'string'))) invalid()
  if (value.deletableRowKeys !== undefined && (!Array.isArray(value.deletableRowKeys) || !value.deletableRowKeys.every(item => typeof item === 'string'))) invalid()
  if (value.lhSupplyRows !== undefined && !lhRows(value.lhSupplyRows)) invalid()
  if (value.unattachedLhSupplyRows !== undefined && !lhRows(value.unattachedLhSupplyRows)) invalid()
  if (value.applicationTypeNames !== undefined && (!isRecord(value.applicationTypeNames) || !Object.values(value.applicationTypeNames).every(item => typeof item === 'string'))) invalid()
  if (value.matchingAreas !== undefined && (!isRecord(value.matchingAreas) || !Object.values(value.matchingAreas).every(item => typeof item === 'number' && Number.isFinite(item)))) invalid()
  if (value.announcementComplexes !== undefined && (!Array.isArray(value.announcementComplexes) || !value.announcementComplexes.every(item => isRecord(item)
    && count(item.id) && item.id > 0 && typeof item.name === 'string' && nullableText(item.roadAddress) && nullableId(item.housingComplexId)
    && nullableText(item.housingComplexName) && [null, 'AUTO', 'ADMIN', 'MIGRATED'].includes(item.linkMethod as string | null) && count(item.rowCount)))) invalid()
  return { announcementComplexes: (value.announcementComplexes ?? []) as AnnouncementComplex[], sourceWarnings: (value.sourceWarnings ?? []) as string[], rowSources: (value.rowSources ?? []) as RowSource[], deletableRowKeys: (value.deletableRowKeys ?? []) as string[], lhSupplyRows: (value.lhSupplyRows ?? []) as LhSupplyRow[], unattachedLhSupplyRows: (value.unattachedLhSupplyRows ?? []) as LhSupplyRow[], applicationTypeNames: (value.applicationTypeNames ?? {}) as Record<string, string>, matchingAreas: (value.matchingAreas ?? {}) as Record<string, number>, snapshotToken: value.snapshotToken,
    version: value.version, rows: value.rows.map(row), sources: value.sources as CorrectionSource[] }
}
const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/supply-corrections`
export async function loadSupplyWorkspace(id: string, signal?: AbortSignal): Promise<SupplyWorkspace> {
  return parseSupplyWorkspace(await requestManagementApi(path(id), 'GET', undefined, signal))
}
/** 공고 단지 하나를 관리자가 정한 단지에 연결하고 그 공고 단지의 공급행도 옮긴다. 화면이 본 뒤 공급정보가 바뀌었으면 409(ADMIN_DATA_STALE)다. */
export async function linkAnnouncementComplex(id: string, announcementComplexId: number, input: { snapshotToken: string; housingComplexId: number }): Promise<SupplyWorkspace> {
  return parseSupplyWorkspace(await requestManagementApi(`${path(id)}/announcement-complexes/${announcementComplexId}/link`, 'POST', input))
}
export async function previewSupply(id: string, input: SupplyCorrectionInput): Promise<SupplyPreview> {
  return parseSupplyPreview(await requestManagementApi(`${path(id)}/preview`, 'POST', input))
}
export function parseSupplyPreview(value: unknown): SupplyPreview {
  if (!isRecord(value) || typeof value.confirmationToken !== 'string' || !Array.isArray(value.before)
    || !Array.isArray(value.after) || !Array.isArray(value.warnings) || !value.warnings.every(item => typeof item === 'string')) invalid()
  return { confirmationToken: value.confirmationToken, before: value.before.map(row), after: value.after.map(row), warnings: value.warnings }
}
export async function applySupply(id: string, correction: SupplyCorrectionInput, confirmationToken: string): Promise<SupplyApplied> {
  const value = await requestManagementApi(path(id), 'POST', { correction, confirmationToken })
  if (!isRecord(value) || !count(value.changeId) || typeof value.alreadyApplied !== 'boolean') invalid()
  return { changeId: value.changeId, alreadyApplied: value.alreadyApplied, workspace: parseSupplyWorkspace(value.workspace) }
}
/**
 * 공통 모집수 정리. 제외한 대상은 묶음에서 빼고, 그 때문에 혼자 남은 대상의 묶음도 푼다(서버는 제외한 대상의 묶음과 대상 하나뿐인 묶음을 거절한다).
 * 풀린 수는 대상만의 수로 볼 수 없어 비워 둔다. PDF 보정과 공급정보 편집이 저장 직전에 함께 쓴다.
 */
export function dropLoneGroups(targets: CorrectionTarget[]) {
  for (const slot of ['householdCountGroup', 'reserveCountGroup'] as const) {
    const members = new Map<string, CorrectionTarget[]>()
    for (const target of targets) {
      const group = target[slot]
      if (group && !target.excluded) members.set(group.key, [...members.get(group.key) ?? [], target])
      else if (group) target[slot] = null
    }
    for (const list of members.values()) if (list.length < 2) list.forEach(target => { target[slot] = null })
  }
}
export function blankTarget(order: number): CorrectionTarget {
  return { id: null, key: crypto.randomUUID(), excluded: false, protectedValues: false, displayOrder: order,
    target: '', supplyRank: null, householdCount: null, reserveCount: null, deposit: null, rent: null, convertedDeposit: null, condition: null }
}
/** 새 공급행은 기존 행 뒤에 둔다. 수집 행의 순서가 1부터 시작해도 겹치지 않게 최댓값 다음 번호를 쓴다. */
export function nextDisplayOrder(rows: CorrectionRow[]) {
  return rows.reduce((next, row) => Math.max(next, row.displayOrder + 1), rows.length)
}
export function blankRow(order: number): CorrectionRow {
  return { id: null, key: crypto.randomUUID(), housingComplexId: null, housingComplexName: null, housingTypeId: null,
    newHousingType: null, groupId: null, sharedHouseholdCount: null, protectedValues: false, excluded: false,
    displayOrder: order, complexName: '', typeName: '', pnu: '', moveInMonth: null, category: 'NEW_SUPPLY',
    householdCount: null, areaOverride: true, exclusiveArea: null, supplyArea: null, targets: [], targetCountsComparable: false }
}

export function parseCorrectionInput(value: unknown): SupplyCorrectionInput {
  if (!isRecord(value) || !['operationId', 'snapshotToken', 'reason', 'sourceUrl'].every(key => typeof value[key] === 'string')
    || !nullableText(value.sourcePage) || !Array.isArray(value.rows)) invalid()
  // 이전 화면이 남긴 보류 입력에는 지금 서버가 받지 않는 필드(newGroups, 행의 saveMode 등)가 있을 수 있어 아는 필드만 복원한다.
  const known = { operationId: value.operationId, snapshotToken: value.snapshotToken, reason: value.reason, sourceUrl: value.sourceUrl,
    sourcePage: value.sourcePage, rows: value.rows.map(item => { const { saveMode: _mode, lhSourceRowIdentifier: _lh, ...rest } = row(item) as CorrectionRow & { saveMode?: unknown; lhSourceRowIdentifier?: unknown }; return rest }),
    ...(Array.isArray(value.deletedRowKeys) ? { deletedRowKeys: value.deletedRowKeys } : {}) }
  return known as SupplyCorrectionInput
}
