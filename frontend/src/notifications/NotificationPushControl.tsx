import { useEffect, useState, useSyncExternalStore } from 'react'
import { Button } from '../design-system/components/Button'
import { useNotificationPush } from './NotificationPushContext'
import { pushUiEnabled } from './firebasePushSdk'
import { pushInstallationHelp } from './pushInstallationHelp'
import { NotificationPushHelp } from './NotificationPushHelp'
import type { PushClient } from './firebasePushClient'
import styles from './NotificationPushControl.module.css'

export function NotificationPushControl({ compact = false }: { readonly compact?: boolean }) {
  const context = useNotificationPush()
  const [helpOpen, setHelpOpen] = useState(false)
  const help = pushInstallationHelp()
  const client = context?.client
  useEffect(() => { void client?.refresh() }, [client])
  if (!pushUiEnabled) return null
  const controls = help ? <PushSwitch checked={false} onClick={() => setHelpOpen(true)} />
    : context?.error ? <><p className={styles.message} role="alert">{compact ? '알림을 연결하지 못했어요.' : context.error}</p><Button variant={compact ? 'secondary' : 'primary'} onClick={context.retry}>다시 시도</Button></>
      : context?.client ? <DeviceControl client={context.client} retry={context.retry} compact={compact} onBlocked={() => setHelpOpen(true)} />
        : <p className={styles.message} role="status">{compact ? '확인 중…' : '기기 설정을 확인하고 있어요…'}</p>
  return <section className={`${styles.control} ${compact ? styles.compact : ''}`} aria-label="이 기기 푸시 설정">
    <div className={styles.heading}><h3>{compact ? '이 기기에서 알림 받기' : '기기 알림'}</h3><span>{compact ? '지금 접속한 브라우저에 적용돼요.' : '이 기기에만 적용'}</span></div>
    {!compact && <p className={styles.description}>새 공고와 신청한 공고의 일정 알림을 받아요.</p>}
    {compact ? <div className={styles.compactControls}>{controls}</div> : controls}
    {!compact && <p className={styles.note}>직접 로그아웃하면 잠시 중지되고, 다시 로그인하면 복원돼요.</p>}
    {helpOpen && <NotificationPushHelp onClose={() => setHelpOpen(false)} />}
  </section>
}
function DeviceControl({ client, retry, compact, onBlocked }: { readonly client: PushClient; readonly retry: () => void; readonly compact: boolean; readonly onBlocked: () => void }) {
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot)
  const working = state.status === 'loading' || state.status === 'busy'
  const [confirmed, setConfirmed] = useState(state.status === 'on')
  useEffect(() => {
    if (state.status === 'on' || state.status === 'off') setConfirmed(state.status === 'on')
  }, [state.status])
  const enabled = state.status === 'on' || (working || state.status === 'error') && confirmed
  return <>
    <PushSwitch checked={enabled} disabled={working || state.status === 'error'} busy={working}
      onClick={() => { if (!enabled && 'Notification' in window && Notification.permission === 'denied') onBlocked(); else void (enabled ? client.disable() : client.enable()) }} />
    {state.message && <p className={styles.message} role={state.status === 'error' ? 'alert' : 'status'}>{state.message}</p>}
    {state.status === 'error' && <Button variant={compact ? 'secondary' : 'primary'} onClick={retry}>다시 시도</Button>}
  </>
}

function PushSwitch({ checked, disabled = false, busy = false, onClick }: {
  readonly checked: boolean; readonly disabled?: boolean; readonly busy?: boolean; readonly onClick: () => void
}) {
  return <div className={styles.switchRow}>
    <span>{busy ? '설정 중…' : checked ? '알림 켜짐' : '알림 꺼짐'}</span>
    <button type="button" role="switch" aria-label="이 기기 알림 받기" aria-checked={checked}
      aria-busy={busy} disabled={disabled} className={styles.switch} onClick={onClick}>
      <span className={styles.thumb} />
    </button>
  </div>
}
