import { useSyncExternalStore } from 'react'
import { useNotificationPush } from './NotificationPushContext'
import { pushUiEnabled } from './firebasePushSdk'
import type { PushClient } from './firebasePushClient'
import styles from './NotificationInbox.module.css'
export function NotificationPushControl() {
  const context = useNotificationPush()
  if (!pushUiEnabled) return null
  return <section className={styles.inbox} aria-label="이 기기 푸시 설정">
    <h3>기기 알림</h3>
    <p>화면을 닫아도 새 공고를 알려 드려요. 공유 기기에서는 로그아웃해 주세요.</p>
    <p>iPhone·iPad는 iOS 16.4 이상에서 홈 화면에 추가한 뒤 사용할 수 있어요.</p>
    {context?.error ? <p role="alert">{context.error} <button type="button" onClick={context.retry}>다시 시도</button></p>
      : context?.client ? <DeviceControl client={context.client} /> : <p role="status">기기 설정을 확인하는 중…</p>}
  </section>
}
function DeviceControl({ client }: { readonly client: PushClient }) {
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot)
  return <>
    {state.message && <p role={state.status === 'error' ? 'alert' : 'status'}>{state.message}</p>}
    {state.status === 'error' ? <p>페이지를 새로고침한 뒤 현재 기기 설정을 확인해 주세요.</p>
      : <button type="button" disabled={state.status === 'loading' || state.status === 'busy'} onClick={() => void (state.status === 'on' ? client.disable() : client.enable())}>
        {state.status === 'loading' || state.status === 'busy' ? '처리 중…' : state.status === 'on' ? '이 기기 알림 끄기' : '이 기기에서 푸시 알림 받기'}
      </button>}
  </>
}
