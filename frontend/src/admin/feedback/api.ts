import { requestManagementApi } from '../management/api'
import { isRecord, parseManagementHistory, type ManagementChange } from '../management/managementContract'

export const feedbackStates = { OPEN: '접수', IN_PROGRESS: '처리 중', ON_HOLD: '보류', DONE: '처리 완료' } as const
export type FeedbackState = keyof typeof feedbackStates
export const relatedResources = { COMPLEX: '단지', ANNOUNCEMENT: '공고' } as const
/** 사용자가 의견을 보낸 공고·단지의 이름. 행을 찾지 못하면 번호로 보인다. */
export function relatedName(entry: Pick<FeedbackEntry, 'relatedId' | 'relatedName'>) {
  return entry.relatedName ?? `#${entry.relatedId}`
}
export type FeedbackEntry = { id: number; content: string; createdAt: string; version: number; status: FeedbackState;
  note: string; relatedResource: '' | 'COMPLEX' | 'ANNOUNCEMENT'; relatedId: number | null; relatedName: string | null;
  actor: string | null; processedAt: string | null }
export type FeedbackInput = { version: number; status: FeedbackState; note: string }

export type FeedbackPage = { items: FeedbackEntry[]; page: number; hasNext: boolean; totalElements: number; totalPages: number }

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export async function listFeedback(params: URLSearchParams, signal?: AbortSignal): Promise<FeedbackPage> {
  const value = await requestManagementApi(`/api/admin/feedback?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.items) || !isCount(value.page) || typeof value.hasNext !== 'boolean'
    || !isCount(value.totalElements) || !isCount(value.totalPages)) {
    throw new Error('사용자 의견 목록 응답이 올바르지 않습니다.')
  }
  return { items: value.items.map(parseEntry), page: value.page, hasNext: value.hasNext,
    totalElements: value.totalElements, totalPages: value.totalPages }
}

function parseEntry(value: unknown): FeedbackEntry {
  if (!isRecord(value) || !isCount(value.id) || value.id === 0 || typeof value.content !== 'string'
    || typeof value.createdAt !== 'string' || !value.createdAt.endsWith('Z') || !Number.isFinite(Date.parse(value.createdAt))) {
    throw new Error('사용자 의견 응답이 올바르지 않습니다.')
  }
  if (!isCount(value.version) || typeof value.status !== 'string' || !Object.hasOwn(feedbackStates, value.status)
    || typeof value.note !== 'string'
    || !['', 'COMPLEX', 'ANNOUNCEMENT'].includes(String(value.relatedResource))
    || !(value.relatedId === null || isCount(value.relatedId) && value.relatedId > 0)
    || !(value.relatedName == null || typeof value.relatedName === 'string')
    || !(value.actor === null || typeof value.actor === 'string')
    || !(value.processedAt === null || typeof value.processedAt === 'string' && Number.isFinite(Date.parse(value.processedAt)))) {
    throw new Error('의견 처리 응답이 올바르지 않습니다.')
  }
  const { id, content, createdAt, version, status, note, relatedResource, relatedId, relatedName, actor, processedAt } = value
  // 이름을 아직 내려주지 않는 이전 서버 응답도 번호로 보이게 null로 맞춘다.
  return { id, content, createdAt, version, status, note, relatedResource, relatedId, relatedName: relatedName ?? null, actor,
    processedAt } as FeedbackEntry
}

export async function saveFeedback(id: number, input: FeedbackInput): Promise<FeedbackEntry> {
  return parseEntry(await requestManagementApi(`/api/admin/feedback/${id}`, 'PUT', input))
}
export async function getFeedback(id: number): Promise<FeedbackEntry> {
  return parseEntry(await requestManagementApi(`/api/admin/feedback/${id}`, 'GET'))
}

export type FeedbackCounts = Record<FeedbackState | 'total', number>

export async function getFeedbackCounts(keyword: string, signal?: AbortSignal): Promise<FeedbackCounts> {
  const params = new URLSearchParams()
  if (keyword) params.set('keyword', keyword)
  const value = await requestManagementApi(`/api/admin/feedback/counts?${params}`, 'GET', undefined, signal)
  if (!isRecord(value) || !isCount(value.OPEN) || !isCount(value.IN_PROGRESS) || !isCount(value.ON_HOLD)
    || !isCount(value.DONE) || !isCount(value.total)) {
    throw new Error('사용자 의견 건수 응답이 올바르지 않습니다.')
  }
  return { OPEN: value.OPEN, IN_PROGRESS: value.IN_PROGRESS, ON_HOLD: value.ON_HOLD, DONE: value.DONE, total: value.total }
}

export async function getFeedbackChanges(id: number, signal?: AbortSignal): Promise<ManagementChange[]> {
  return parseManagementHistory(await requestManagementApi(`/api/admin/feedback/${id}/changes`, 'GET', undefined, signal))
}

const seoulTime = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
function seoulParts(iso: string) {
  return Object.fromEntries(seoulTime.formatToParts(new Date(iso)).map(part => [part.type, part.value]))
}
/** 한국 시각 MM.DD HH:mm */
export function formatShortTime(iso: string) {
  const p = seoulParts(iso)
  return `${p.month}.${p.day} ${p.hour}:${p.minute}`
}
/** 한국 시각 YYYY.MM.DD HH:mm */
export function formatLongTime(iso: string) {
  const p = seoulParts(iso)
  return `${p.year}.${p.month}.${p.day} ${p.hour}:${p.minute}`
}
