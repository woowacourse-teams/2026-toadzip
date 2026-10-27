import { useEffect, useState, type ReactNode } from 'react'
import { createPushClient, type PushClient } from './firebasePushClient'
import { preparePushSdk, pushUiEnabled } from './firebasePushSdk'
import { pushDeviceRepository, type PushDeviceState } from './pushDeviceRepository'
import { NotificationPushContext } from './NotificationPushContext'
import { useNotificationInbox } from './NotificationInboxContext'
import { pushInstallationHelp } from './pushInstallationHelp'
import { registerPushLogoutHandler } from './pushSession'
export function NotificationPushProvider({ children }: { readonly children: ReactNode }) {
  const inbox = useNotificationInbox()
  const userId = inbox?.userId ?? null
  const invalidate = inbox?.invalidate
  const [state, setState] = useState<{ userId: string | null; client: PushClient | null; current: PushDeviceState | null; error: string }>({ userId: null, client: null, current: null, error: '' })
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!userId || !pushUiEnabled) return
    let alive = true
    const controller = new AbortController()
    let client: PushClient | null = null
    let stopLogout: (() => void) | undefined
    void Promise.all([pushInstallationHelp() ? Promise.resolve(null) : preparePushSdk(), pushDeviceRepository.current(controller.signal)]).then(async ([sdk, current]) => {
      if (!alive) return
      if (!sdk) { setState({ userId, client: null, current, error: '' }); return }
      client = createPushClient(userId, sdk, pushDeviceRepository)
      client.subscribeRefresh(() => invalidate?.())
      stopLogout = registerPushLogoutHandler(() => client?.afterLogout() ?? Promise.resolve())
      setState({ userId, client, current, error: '' })
      await client.start(current)
    }).catch(() => { if (alive) setState({ userId, client: null, current: null, error: '기기 알림을 준비하지 못했어요. 잠시 후 다시 시도해 주세요.' }) })
    return () => { alive = false; controller.abort(); stopLogout?.(); client?.dispose() }
  }, [userId, invalidate, retry])
  useEffect(() => {
    const client = state.userId === userId ? state.client : null
    if (!client) return
    const refresh = () => {
      if (document.visibilityState === 'visible' && navigator.onLine !== false) void client.refresh()
    }
    const leave = () => client.invalidateRefresh()
    const visibilityChanged = () => { if (document.hidden) leave(); else refresh() }
    window.addEventListener('focus', refresh)
    window.addEventListener('online', refresh)
    window.addEventListener('blur', leave)
    window.addEventListener('offline', leave)
    document.addEventListener('visibilitychange', visibilityChanged)
    return () => {
      window.removeEventListener('focus', refresh)
      window.removeEventListener('online', refresh)
      window.removeEventListener('blur', leave)
      window.removeEventListener('offline', leave)
      document.removeEventListener('visibilitychange', visibilityChanged)
    }
  }, [state.client, state.userId, userId])
  return <NotificationPushContext.Provider value={{ current: state.userId === userId ? state.current : null, client: state.userId === userId ? state.client : null, error: state.userId === userId ? state.error : '', retry: () => { setState({ userId, client: null, current: null, error: '' }); setRetry(value => value + 1) } }}>{children}</NotificationPushContext.Provider>
}
