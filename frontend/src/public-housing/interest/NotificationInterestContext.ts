import { createContext, useContext } from 'react'
import type { NotificationEventSource, NotificationTarget } from './notificationInterestRepository'

export interface Selection { readonly target: NotificationTarget; readonly source: NotificationEventSource }
export const InterestContext = createContext<{
  readonly userId: string | null
  readonly updating?: boolean
  readonly blocked: boolean
  readonly clearingAll: boolean
  readonly batchError: string
  readonly setFeedbackContainer?: (element: HTMLElement | null) => void
  readonly clearAll: (trigger: HTMLButtonElement) => void
  readonly mode: 'loading' | 'guest' | 'member' | 'error'
  /** 상태 조회 실패 뒤 알림 버튼을 눌러 다시 불러오는 중인지 */
  readonly retrying: boolean
  readonly requested: ReadonlyMap<string, boolean>
  readonly targets: readonly NotificationTarget[]
  readonly resetSession: () => void
  readonly refreshStatus: () => void
  readonly request: (selection: Selection, trigger: HTMLButtonElement) => void
  readonly expose: (selection: Selection) => void
} | null>(null)

export function useNotificationInterests() { return useContext(InterestContext) }
