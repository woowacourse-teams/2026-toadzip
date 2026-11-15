import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'
import { housingTypeCount, type SupplySheet } from '../supply/supplySheet'
import type { Review, ReviewSection } from './review'

/** 초안을 만들 때의 공급표 주택형 요약. 복원할 때 지금 공급표와 비교해 무엇이 바뀌었는지 보여 준다. */
export type DraftBaseType = { key: string; label: string; householdCount: number | null; exclusiveArea: number | null }
/**
 * 화면 작업 상태. review에 칸마다 기존·추출·직접 입력 선택과 미완성 입력, 단지·주택타입 연결,
 * 일정·접수처 선택, 확인 진행이 모두 들어 있다. text는 추출 응답 원문, reason·sourceUrl·sourcePage는 적용 근거다.
 * version 4부터 공급정보는 공고 공급표(주택형 × 공급대상 칸)이고 공급대상마다 분류가 있다. 이전 초안은 이어 쓸 수 없다.
 */
export const draftVersion = 4
export type DraftPayload = { version: typeof draftVersion; text: string; review: Review; reason: string; sourceUrl: string; sourcePage: string; section: ReviewSection; pdfName: string; baseSheet: DraftBaseType[]
  /** 추출할 때 AI 워커가 남긴 경고(LH 행 분리 등). 이 필드 전에 저장한 초안에는 없다. */
  warnings?: string[]
  /** 편집 영역에 보이던 주택형 번호. 이 주택형이 속한 단지로 복원한다. */
  selectedRow?: number }
/** 이전 방식(지금보다 낮은 version)으로 저장한 초안. 복원하지 않고 그 revision을 이어받아 덮어쓴다. */
export type OutdatedDraft = { outdated: true; revision: number }
export type CorrectionDraft = { payload: DraftPayload; revision: number; baseSnapshotToken: string; pdfSha256: string | null; extractionId: number | null; updatedAt: string; stale: boolean }
export type DraftSaveInput = { expectedRevision: number | null; baseSnapshotToken: string; pdfSha256?: string; extractionId?: number; payload: DraftPayload }
export const draftConflictCode = 'ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT'

const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/correction-draft`
function invalid(): never { throw new Error('임시저장 응답이 올바르지 않습니다.') }
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0

function payload(value: unknown): DraftPayload {
  if (!isRecord(value) || value.version !== draftVersion || typeof value.text !== 'string' || !isRecord(value.review) || !Array.isArray(value.review.rows)
    || !Array.isArray(value.review.schedules) || !Array.isArray(value.baseSheet) || typeof value.reason !== 'string' || typeof value.sourceUrl !== 'string'
    || value.warnings !== undefined && !(Array.isArray(value.warnings) && value.warnings.every(item => typeof item === 'string'))) invalid()
  return value as DraftPayload
}

/** 이 관리자의 초안. 없으면 null, 이전 방식 초안이면 OutdatedDraft다. stale은 초안을 만든 뒤 공고 작업 공간이 바뀌었다는 뜻이다. */
export async function loadCorrectionDraft(id: string, signal?: AbortSignal): Promise<CorrectionDraft | OutdatedDraft | null> {
  const value = await requestManagementApi(path(id), 'GET', undefined, signal)
  if (value === null) return null
  if (!isRecord(value) || !positive(value.revision) || typeof value.baseSnapshotToken !== 'string' || typeof value.updatedAt !== 'string' || typeof value.stale !== 'boolean'
    || value.pdfSha256 != null && typeof value.pdfSha256 !== 'string' || value.extractionId != null && !positive(value.extractionId)) invalid()
  const version = isRecord(value.payload) ? value.payload.version : undefined
  if (typeof version === 'number' && Number.isSafeInteger(version) && version < draftVersion) return { outdated: true, revision: value.revision }
  return { payload: payload(value.payload), revision: value.revision, baseSnapshotToken: value.baseSnapshotToken, pdfSha256: (value.pdfSha256 as string | null | undefined) ?? null,
    extractionId: (value.extractionId as number | null | undefined) ?? null, updatedAt: value.updatedAt, stale: value.stale }
}
/** 저장된 revision이 expectedRevision일 때만 저장한다. 다르면 409(ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT)이고 초안은 그대로다. */
export async function saveCorrectionDraft(id: string, input: DraftSaveInput, signal?: AbortSignal): Promise<{ revision: number; updatedAt: string }> {
  const value = await requestManagementApi(path(id), 'PUT', input, signal)
  if (!isRecord(value) || !positive(value.revision) || typeof value.updatedAt !== 'string') invalid()
  return { revision: value.revision, updatedAt: value.updatedAt }
}
export async function deleteCorrectionDraft(id: string, expectedRevision: number) {
  await requestManagementApi(`${path(id)}?expectedRevision=${expectedRevision}`, 'DELETE')
}

export function draftBaseSheet(sheet: SupplySheet): DraftBaseType[] {
  return sheet.housingTypes.map(type => ({ key: type.key, label: `${type.housingComplexName ?? type.complexName} · ${type.name}`, householdCount: housingTypeCount(sheet, type.key), exclusiveArea: type.exclusiveArea }))
}
/** 초안을 만든 뒤 바뀐 주택형. 사라진 주택형·새 주택형·모집수·전용면적·표기가 바뀐 주택형을 적는다. */
export function baseSheetChanges(saved: DraftBaseType[], current: SupplySheet): string[] {
  const now = new Map(draftBaseSheet(current).map(type => [type.key, type]))
  const before = new Map(saved.map(type => [type.key, type]))
  const changes = saved.flatMap(type => {
    const next = now.get(type.key)
    if (!next) return [`${type.label} 주택형이 없어졌습니다.`]
    const fields = [type.householdCount !== next.householdCount ? `모집수 ${type.householdCount ?? '미확인'} → ${next.householdCount ?? '미확인'}` : '',
      type.exclusiveArea !== next.exclusiveArea ? `전용 ${type.exclusiveArea ?? '미확인'} → ${next.exclusiveArea ?? '미확인'}㎡` : '', type.label !== next.label ? `표기 ${type.label} → ${next.label}` : ''].filter(Boolean)
    return fields.length ? [`${type.label}: ${fields.join(' · ')}`] : []
  })
  return [...changes, ...[...now.values()].filter(type => !before.has(type.key)).map(type => `${type.label} 주택형이 새로 생겼습니다.`)]
}
