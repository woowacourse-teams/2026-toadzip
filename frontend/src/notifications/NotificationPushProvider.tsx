import { useEffect, useState, type ReactNode } from 'react'
import { createPushClient, type PushClient } from './firebasePushClient'
import { preparePushSdk, pushUiEnabled } from './firebasePushSdk'
import { pushDeviceRepository } from './pushDeviceRepository'
import { NotificationPushContext } from './NotificationPushContext'
import { useNotificationInbox } from './NotificationInboxContext'
import { registerPushLogoutHandler } from './pushSession'
export function NotificationPushProvider({ children }: { readonly children: ReactNode }) {
  const inbox = useNotificationInbox()
  const userId = inbox?.userId ?? null
  const invalidate = inbox?.invalidate
  const [state, setState] = useState<{ userId: string | null; client: PushClient | null; error: string }>({ userId: null, client: null, error: '' })
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!userId || !pushUiEnabled) return
    let alive = true
    let client: PushClient | null = null
    let stopLogout: (() => void) | undefined
    void preparePushSdk().then(async sdk => {
      if (!alive) return
      client = createPushClient(userId, sdk, pushDeviceRepository)
      client.subscribeRefresh(() => invalidate?.())
      stopLogout = registerPushLogoutHandler(() => client?.afterLogout() ?? Promise.resolve())
      setState({ userId, client, error: '' })
      await client.start()
    }).catch(error => { if (alive) setState({ userId, client: null, error: error instanceof Error ? error.message : '푸시 알림을 준비하지 못했어요.' }) })
    return () => { alive = false; stopLogout?.(); client?.dispose() }
  }, [userId, invalidate, retry])
  return <NotificationPushContext.Provider value={{ client: state.userId === userId ? state.client : null, error: state.userId === userId ? state.error : '', retry: () => setRetry(value => value + 1) }}>{children}</NotificationPushContext.Provider>
}
