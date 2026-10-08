import { createContext, useContext } from 'react'
import type { NotificationEventSource, NotificationTarget } from './notificationInterestRepository'

export interface Selection { readonly target: NotificationTarget; readonly source: NotificationEventSource }
export const InterestContext = createContext<{
  readonly blocked: boolean
  readonly clearingAll: boolean
  readonly clearAll: (trigger: HTMLButtonElement) => void
  readonly mode: 'loading' | 'guest' | 'member' | 'error'
  readonly requested: ReadonlyMap<string, boolean>
  readonly targets: readonly NotificationTarget[]
  readonly resetSession: () => void
  readonly refreshStatus: () => void
  readonly request: (selection: Selection, trigger: HTMLButtonElement) => void
  readonly expose: (selection: Selection) => void
} | null>(null)

export const notificationPreparationTitle = '알림 기능을 준비하고 있어요'
export const notificationPreparationDescription = '관심 지역/단지의 새 공고를 알림 보관함에서 확인할 수 있도록 준비 중이에요.'
export const notificationPreparationNotice = '기능이 열리면 서비스 안에서 안내해 드릴게요.'

export function useNotificationInterests() { return useContext(InterestContext) }
