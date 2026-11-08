import { createContext, useContext } from 'react'
import type { InputOrigin, InputRequest } from './conditionInput'
import { EMPTY_PROFILE, type ConditionsRepository, type ProfileField, type StoredConditionProfile } from './conditionsModel'

export type ConditionsMode = 'disabled' | 'loading' | 'guest' | 'member' | 'error'
export type NudgeVariant = 'detail' | 'afterLogin' | 'list'

export interface LastSave {
  readonly revision: number
  readonly origin: InputOrigin
  readonly kind: 'full' | 'partial' | 'clear'
  readonly fields: readonly ProfileField[]
  readonly announcementId: string | null
}

export interface OpenInputOptions {
  readonly announcementId?: string | null
  /** 작성하던 내용이 있으면 멈춘 단계에서 이어서 연다 */
  readonly resume?: boolean
}

export interface ConditionsContextValue {
  readonly mode: ConditionsMode
  readonly repository: ConditionsRepository
  readonly stored: StoredConditionProfile | null
  readonly hasProfile: boolean
  /** 회원이 처음 입력하다 멈춘 단계(4는 입력 완료) */
  readonly memberDraft: { readonly step: number } | null
  /** 저장할 때마다 늘어난다. 비교 결과를 다시 불러오는 신호 */
  readonly revision: number
  readonly lastSave: LastSave | null
  readonly openInput: (request: InputRequest, options?: OpenInputOptions) => void
  readonly restartInput: (origin: InputOrigin, announcementId?: string | null) => void
  readonly openNudge: (variant: NudgeVariant, options?: { readonly announcementId?: string | null }) => void
  readonly clearProfile: () => void
  readonly notify: (message: string) => void
}

const noop = () => {}
const disabledRepository: ConditionsRepository = {
  loadProfile: async () => ({ version: 0, savedAt: null, profile: EMPTY_PROFILE }),
  saveProfile: async () => ({ version: 0, savedAt: null, profile: EMPTY_PROFILE }),
  loadComparison: async (ref) => ({ announcementId: ref.announcementId, availability: 'NOT_STRUCTURED', referenceDate: '', requiredQualification: null, types: [] }),
  loadListResults: async () => new Map(),
}

const DISABLED: ConditionsContextValue = {
  mode: 'disabled', repository: disabledRepository, stored: null, hasProfile: false, memberDraft: null, revision: 0, lastSave: null,
  openInput: noop, restartInput: noop, openNudge: noop, clearProfile: noop, notify: noop,
}

export const ConditionsContext = createContext<ConditionsContextValue>(DISABLED)

export function useConditions(): ConditionsContextValue {
  return useContext(ConditionsContext)
}
