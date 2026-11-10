// 비회원 입력은 저장하지 않는다. 로그인 왕복 동안만 sessionStorage에 넘겨 주고 저장 직후 지운다.
// 회원이 처음 입력할 때만 이 기기에 작성 중인 내용을 7일 동안 둔다.
import type { ConditionDraft } from './conditionDraft'
import type { InputOrigin } from './conditionInput'

const HANDOFF_KEY = 'toadzip:condition-handoff'
const HANDOFF_AGE = 30 * 60 * 1000
const DRAFT_AGE = 7 * 24 * 60 * 60 * 1000
const NUDGE_SHOWN_KEY = 'toadzip:condition-nudge-shown'
const NUDGE_SNOOZE_KEY = 'toadzip:condition-nudge-snooze-until'
const BANNER_HIDDEN_KEY = 'toadzip:condition-banner-hidden'
const draftKey = (userId: string) => `toadzip:condition-draft:${userId}`

export interface LoginHandoff {
  readonly draft: ConditionDraft
  readonly origin: InputOrigin
  readonly announcementId: string | null
}

export interface MemberDraft {
  readonly draft: ConditionDraft
  readonly step: number
  readonly maxStep: number
}

function isDraft(value: unknown): value is ConditionDraft {
  if (typeof value !== 'object' || value === null) return false
  const draft = value as Partial<ConditionDraft>
  return typeof draft.answers === 'object' && draft.answers !== null && !Array.isArray(draft.answers)
    && (draft.familyDetails === null || draft.familyDetails === 'PROVIDED' || draft.familyDetails === 'SKIPPED')
    && (draft.provinceCode === null || typeof draft.provinceCode === 'string')
}

function readJson(storage: Storage, key: string): unknown {
  try { return JSON.parse(storage.getItem(key) ?? 'null') } catch { return null }
}

function write(storage: Storage, key: string, value: unknown) {
  try { storage.setItem(key, JSON.stringify(value)) } catch { /* 저장소를 쓸 수 없으면 기억하지 않는다. */ }
}

function remove(storage: Storage, key: string) {
  try { storage.removeItem(key) } catch { /* 무시 */ }
}

export function saveLoginHandoff(handoff: LoginHandoff, now = new Date()) {
  write(sessionStorage, HANDOFF_KEY, { ...handoff, expiresAt: now.getTime() + HANDOFF_AGE })
}

export function takeLoginHandoff(now = new Date()): LoginHandoff | null {
  const value = readJson(sessionStorage, HANDOFF_KEY) as Partial<LoginHandoff & { expiresAt: number }> | null
  remove(sessionStorage, HANDOFF_KEY)
  if (!value || typeof value !== 'object' || typeof value.expiresAt !== 'number' || value.expiresAt <= now.getTime()) return null
  if (!isDraft(value.draft) || !['detail', 'list', 'mypage'].includes(value.origin as string)) return null
  return { draft: value.draft, origin: value.origin as InputOrigin, announcementId: typeof value.announcementId === 'string' ? value.announcementId : null }
}

export function hasLoginHandoff(): boolean {
  return readJson(sessionStorage, HANDOFF_KEY) !== null
}

export function saveMemberDraft(userId: string, saved: MemberDraft, now = new Date()) {
  write(localStorage, draftKey(userId), { ...saved, savedAt: now.getTime() })
}

export function loadMemberDraft(userId: string, now = new Date()): MemberDraft | null {
  const value = readJson(localStorage, draftKey(userId)) as Partial<MemberDraft & { savedAt: number }> | null
  if (!value || typeof value !== 'object') return null
  if (typeof value.savedAt !== 'number' || now.getTime() - value.savedAt > DRAFT_AGE || !isDraft(value.draft)) {
    remove(localStorage, draftKey(userId))
    return null
  }
  return { draft: value.draft, step: Number(value.step) || 1, maxStep: Number(value.maxStep) || 1 }
}

export function clearMemberDraft(userId: string) {
  remove(localStorage, draftKey(userId))
}

export function markNudgeShown() {
  write(sessionStorage, NUDGE_SHOWN_KEY, true)
}

export function nudgeShownThisVisit(): boolean {
  return readJson(sessionStorage, NUDGE_SHOWN_KEY) === true
}

export function snoozeNudge(now = new Date()) {
  write(localStorage, NUDGE_SNOOZE_KEY, now.getTime() + DRAFT_AGE)
}

export function nudgeSnoozed(now = new Date()): boolean {
  const until = readJson(localStorage, NUDGE_SNOOZE_KEY)
  return typeof until === 'number' && until > now.getTime()
}

export function hideBannerThisVisit() {
  write(sessionStorage, BANNER_HIDDEN_KEY, true)
}

export function bannerHiddenThisVisit(): boolean {
  return readJson(sessionStorage, BANNER_HIDDEN_KEY) === true
}
