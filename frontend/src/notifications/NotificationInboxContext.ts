import { createContext, useContext } from 'react'
import type { NotificationRepository } from './notificationRepository'
export interface InboxContextValue {
  readonly userId: string | null; readonly unreadCount: number | null; readonly error: string | null
  readonly repository: NotificationRepository; readonly revision: number
  readonly invalidate: (broadcast?: boolean) => void; readonly sessionExpired: () => void
}
export const NotificationInboxContext = createContext<InboxContextValue | null>(null)
export function useNotificationInbox() { return useContext(NotificationInboxContext) }
