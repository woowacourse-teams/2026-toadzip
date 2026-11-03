import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import type { Review, ReviewSection } from './review'

/** 초안을 만들 때의 공급행 요약. 복원할 때 지금 공급행과 비교해 무엇이 바뀌었는지 보여 준다. */
export type DraftBaseRow = { key: string; id: number | null; label: string; householdCount: number | null; exclusiveArea: number | null; excluded: boolean }
/**
 * 화면 작업 상태. review에 행 포함·제외, 칸마다 기존·추출·직접 입력 선택과 미완성 입력, 저장 방식(분리·연결·추가), 단지·주택형 연결,
 * 일정·접수처 선택, 확인 진행이 모두 들어 있다. text는 추출 응답 원문, reason·sourceUrl·sourcePage는 적용 근거다.
 */
export type DraftPayload = { version: 1; text: string; review: Review; reason: string; sourceUrl: string; sourcePage: string; section: ReviewSection; pdfName: string; baseRows: DraftBaseRow[]
  /** 추출할 때 AI 워커가 남긴 경고(LH 행 분리 등). 이 필드 전에 저장한 초안에는 없다. */
  warnings?: string[]
  /** 편집 영역에 보이던 공급행 번호. 이 행이 속한 단지 탭으로 복원한다. 이 필드 전에 저장한 초안에는 없다. */
  selectedRow?: number }
export type CorrectionDraft = { payload: DraftPayload; revision: number; baseSnapshotToken: string; pdfSha256: string | null; extractionId: number | null; updatedAt: string; stale: boolean }
export type DraftSaveInput = { expectedRevision: number | null; baseSnapshotToken: string; pdfSha256?: string; extractionId?: number; payload: DraftPayload }
export const draftConflictCode = 'ANNOUNCEMENT_CORRECTION_DRAFT_CONFLICT'

const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/correction-draft`
function invalid(): never { throw new Error('임시저장 응답이 올바르지 않습니다.') }
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0

function payload(value: unknown): DraftPayload {
  if (!isRecord(value) || value.version !== 1 || typeof value.text !== 'string' || !isRecord(value.review) || !Array.isArray(value.review.rows)
    || !Array.isArray(value.review.schedules) || !Array.isArray(value.baseRows) || typeof value.reason !== 'string' || typeof value.sourceUrl !== 'string'
    || value.warnings !== undefined && !(Array.isArray(value.warnings) && value.warnings.every(item => typeof item === 'string'))) invalid()
  return value as DraftPayload
}

/** 이 관리자의 초안. 없으면 null이다. stale은 초안을 만든 뒤 공고 작업 공간이 바뀌었다는 뜻이다. */
export async function loadCorrectionDraft(id: string, signal?: AbortSignal): Promise<CorrectionDraft | null> {
  const value = await requestManagementApi(path(id), 'GET', undefined, signal)
  if (value === null) return null
  if (!isRecord(value) || !positive(value.revision) || typeof value.baseSnapshotToken !== 'string' || typeof value.updatedAt !== 'string' || typeof value.stale !== 'boolean'
    || value.pdfSha256 != null && typeof value.pdfSha256 !== 'string' || value.extractionId != null && !positive(value.extractionId)) invalid()
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

export function draftBaseRows(rows: CorrectionRow[]): DraftBaseRow[] {
  return rows.map(row => ({ key: row.key, id: row.id, label: `${row.housingComplexName ?? row.complexName} · ${row.typeName}`, householdCount: row.householdCount, exclusiveArea: row.exclusiveArea, excluded: row.excluded }))
}
/** 초안을 만든 뒤 바뀐 공급행. 사라진 행·새 행·모집수·전용면적·제외가 바뀐 행을 적는다. */
export function baseRowChanges(saved: DraftBaseRow[], current: CorrectionRow[]): string[] {
  const now = new Map(draftBaseRows(current).map(row => [row.key, row]))
  const before = new Map(saved.map(row => [row.key, row]))
  const name = (row: DraftBaseRow) => `${row.label} (#${String(row.id)})`
  const changes = saved.flatMap(row => {
    const next = now.get(row.key)
    if (!next) return [`${name(row)} 행이 없어졌습니다.`]
    const fields = [row.householdCount !== next.householdCount ? `모집수 ${row.householdCount ?? '미확인'} → ${next.householdCount ?? '미확인'}` : '',
      row.exclusiveArea !== next.exclusiveArea ? `전용 ${row.exclusiveArea ?? '미확인'} → ${next.exclusiveArea ?? '미확인'}㎡` : '',
      row.excluded !== next.excluded ? (next.excluded ? '제외됨' : '제외 해제됨') : '', row.label !== next.label ? `표기 ${row.label} → ${next.label}` : ''].filter(Boolean)
    return fields.length ? [`${name(row)}: ${fields.join(' · ')}`] : []
  })
  return [...changes, ...[...now.values()].filter(row => !before.has(row.key)).map(row => `${name(row)} 행이 새로 생겼습니다.`)]
}
