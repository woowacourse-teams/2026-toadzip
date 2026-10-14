import { createContext, useContext } from 'react'
import type { PushClient } from './firebasePushClient'
export const NotificationPushContext = createContext<{ readonly client: PushClient | null; readonly error: string; readonly retry: () => void } | null>(null)
export function useNotificationPush() { return useContext(NotificationPushContext) }
