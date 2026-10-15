import { createContext, useContext } from 'react'
import type { NotificationEventSource, NotificationTarget } from './notificationInterestRepository'

export interface Selection { readonly target: NotificationTarget; readonly source: NotificationEventSource }
export const InterestContext = createContext<{
  readonly userId: string | null
  readonly blocked: boolean
  readonly clearingAll: boolean
  readonly batchError: string
  readonly setFeedbackContainer?: (element: HTMLElement | null) => void
  readonly clearAll: (trigger: HTMLButtonElement) => void
  readonly mode: 'loading' | 'guest' | 'member' | 'error'
  readonly requested: ReadonlyMap<string, boolean>
  readonly targets: readonly NotificationTarget[]
  readonly resetSession: () => void
  readonly refreshStatus: () => void
  readonly request: (selection: Selection, trigger: HTMLButtonElement) => void
  readonly expose: (selection: Selection) => void
} | null>(null)

export function useNotificationInterests() { return useContext(InterestContext) }
