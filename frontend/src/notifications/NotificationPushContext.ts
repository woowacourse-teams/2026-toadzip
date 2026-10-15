import { createContext, useContext } from 'react'
import type { PushDeviceState } from './pushDeviceRepository'
import type { PushClient } from './firebasePushClient'
export const NotificationPushContext = createContext<{ readonly client: PushClient | null; readonly current?: PushDeviceState | null; readonly error: string; readonly retry: () => void } | null>(null)
export function useNotificationPush() { return useContext(NotificationPushContext) }
