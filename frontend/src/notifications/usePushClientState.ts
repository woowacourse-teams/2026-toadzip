import { useSyncExternalStore } from 'react'
import type { PushClient, PushState } from './firebasePushClient'
const idle: PushState = { status: 'loading', message: '' }
const subscribe = () => () => {}
const snapshot = () => idle
export function usePushClientState(client: PushClient | null | undefined) {
  const getSnapshot = client?.getSnapshot ?? snapshot
  return useSyncExternalStore(client?.subscribe ?? subscribe, getSnapshot, getSnapshot)
}
