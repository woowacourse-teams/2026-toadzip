import { useSyncExternalStore } from 'react'
import type { PushClient, PushState } from './firebasePushClient'
const idle: PushState = { status: 'loading', message: '' }
const subscribe = () => () => {}
const snapshot = () => idle
export function usePushClientState(client: PushClient | null | undefined) {
  return useSyncExternalStore(client?.subscribe ?? subscribe, client?.getSnapshot ?? snapshot)
}
