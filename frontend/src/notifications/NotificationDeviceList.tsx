import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '../design-system/components/Button'
import { useNotificationInbox } from './NotificationInboxContext'
import { useNotificationPush } from './NotificationPushContext'
import { pushUiEnabled } from './firebasePushSdk'
import { pushDeviceRepository, type PushDeviceSummary } from './pushDeviceRepository'
import { lastUsedLabel, pushDeviceLabel } from './pushDeviceMetadata'
import { usePushClientState } from './usePushClientState'
import styles from './NotificationDeviceList.module.css'

export function NotificationDeviceList() {
  const inbox = useNotificationInbox()
  if (!pushUiEnabled || !inbox?.userId) return null
  return <DeviceDisclosure key={inbox.userId} userId={inbox.userId} />
}
function DeviceDisclosure({ userId }: { readonly userId: string }) {
  const [expanded, setExpanded] = useState(false)
  const contentId = useId()
  const heading = useRef<HTMLButtonElement>(null)
  return <section className={styles.section} aria-label="알림 받는 기기">
    <h3 className={styles.heading}><button ref={heading} type="button" className={styles.disclosure}
      aria-expanded={expanded} aria-controls={contentId} onClick={() => setExpanded(value => !value)}>
      <span>알림 받는 기기</span><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 9 6 6 6-6" /></svg>
    </button></h3>
    <div id={contentId} hidden={!expanded}>
      {expanded && <DeviceList userId={userId} onDisabled={() => heading.current?.focus()} />}
    </div>
  </section>
}
function DeviceList({ userId, onDisabled }: { readonly userId: string; readonly onDisabled: () => void }) {
  const push = useNotificationPush()
  const pushState = usePushClientState(push?.client)
  const [devices, setDevices] = useState<readonly PushDeviceSummary[] | null>(null)
  const [revision, setRevision] = useState(0)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const mutation = useRef<AbortController | null>(null)
  const requestVersion = useRef(0)
  useEffect(() => () => mutation.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController()
    const version = ++requestVersion.current
    setError('')
    void pushDeviceRepository.devices(controller.signal).then(result => {
      if (!controller.signal.aborted && requestVersion.current === version) setDevices(result)
    }).catch(() => { if (!controller.signal.aborted && requestVersion.current === version) setError('기기 목록을 불러오지 못했어요. 다시 조회해 주세요.') })
    return () => controller.abort()
  }, [revision, pushState.status])
  const disable = async (device: PushDeviceSummary) => {
    if (mutation.current && !mutation.current.signal.aborted) return
    const controller = new AbortController()
    mutation.current = controller
    requestVersion.current++
    setBusy(device.deviceId); setError(''); setMessage('')
    try {
      await pushDeviceRepository.disableDevice(device.deviceId, userId, device.bindingVersion, controller.signal)
      if (controller.signal.aborted) return
      requestVersion.current++
      setDevices(items => items?.filter(item => item.deviceId !== device.deviceId) ?? null)
      setMessage(`${pushDeviceLabel(device.platform, device.browser)}의 알림을 껐어요. 관심 알림은 그대로 유지돼요.`)
      onDisabled()
    } catch {
      if (!controller.signal.aborted) setError('알림을 해제하지 못했어요. 기기 목록을 다시 조회한 뒤 시도해 주세요.')
    } finally {
      if (!controller.signal.aborted) { mutation.current = null; setBusy(null) }
    }
  }
  const visibleDevices = devices?.filter(device => device.deviceId !== busy) ?? null
  return <div className={styles.content}>
    <div className={styles.actions}>
      <button type="button" className={styles.refresh} disabled={busy !== null} aria-label="기기 목록 다시 조회" onClick={() => setRevision(value => value + 1)}>새로고침</button></div>
    {error && <p className={styles.feedback} role="alert">{error}</p>}
    {message && <p className={styles.feedback} role="status">{message}</p>}
    {!devices && !error && <p role="status">기기 목록을 불러오는 중…</p>}
    {visibleDevices?.length === 0 && <p className={styles.empty}>알림을 받는 기기가 없어요.</p>}
    {!!visibleDevices?.length && <ul className={styles.list}>{[...visibleDevices].sort((a, b) => Number(b.current) - Number(a.current)).map(device => {
      const label = pushDeviceLabel(device.platform, device.browser)
      return <li key={device.deviceId}>
        <div className={styles.details}><div className={styles.deviceTitle}><strong>{label}</strong>{device.current && <span className={styles.badge}>현재 사용 중</span>}</div>
          <span className={styles.lastUsed}>마지막 사용 · {lastUsedLabel(device.lastSeenAt)}</span>
          {device.resumeAfterLogin && <span className={styles.paused}>로그아웃으로 일시 중지</span>}
        </div>
        {!device.current && <Button disabled={busy !== null} aria-label={`${label} 알림 해제`} onClick={() => void disable(device)}>알림 해제</Button>}
      </li>
    })}</ul>}
  </div>
}
