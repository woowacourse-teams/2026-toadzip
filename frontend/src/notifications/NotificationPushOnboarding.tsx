import { useEffect, useState } from 'react'
import { Button } from '../design-system/components/Button'
import { useNotificationInterests } from '../public-housing/interest/NotificationInterestContext'
import { NotificationDialog } from './NotificationDialog'
import { useNotificationPush } from './NotificationPushContext'
import { NotificationPushHelp } from './NotificationPushHelp'
import { pushUiEnabled } from './firebasePushSdk'
import { pushInstallationHelp } from './pushInstallationHelp'
import { usePushClientState } from './usePushClientState'
import styles from './NotificationPushOnboarding.module.css'

// Functional preference only: never sent to analytics or shared between accounts.
const dismissedInMemory = new Set<string>()
const storageKey = (userId: string) => `toadzip.push-invitation.${userId}`
function wasDismissed(userId: string) {
  if (dismissedInMemory.has(userId)) return true
  try { return localStorage.getItem(storageKey(userId)) === 'dismissed' }
  catch { return false }
}
function rememberDismissal(userId: string) {
  try { localStorage.setItem(storageKey(userId), 'dismissed'); dismissedInMemory.delete(userId) }
  catch { dismissedInMemory.add(userId) }
}
export function NotificationPushOnboarding() {
  const interests = useNotificationInterests()
  if (!pushUiEnabled || interests?.mode !== 'member' || !interests.userId) return null
  return <Invitation key={interests.userId} userId={interests.userId} />
}
function Invitation({ userId }: { readonly userId: string }) {
  const interests = useNotificationInterests()
  const context = useNotificationPush()
  const state = usePushClientState(context?.client)
  const [stage, setStage] = useState<'pending' | 'offer' | 'help' | 'done' | 'closed'>(() => wasDismissed(userId) ? 'closed' : 'pending')
  const [attempted, setAttempted] = useState(false)
  const help = pushInstallationHelp()
  const current = context?.current
  useEffect(() => {
    if (stage !== 'pending' || !current || interests?.blocked || (!help && state.status === 'loading')) return
    if (!interests?.targets.length || current.knownForUser !== false || current.enabled || current.resumeAfterLogin) {
      setStage('closed'); return
    }
    const offer = () => { if (!document.querySelector('dialog[open]')) setStage('offer') }
    offer()
    const observer = new MutationObserver(offer)
    observer.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['open'] })
    return () => observer.disconnect()
  }, [current, help, interests?.blocked, interests?.targets.length, stage, state.status])
  useEffect(() => {
    if (stage === 'offer' && state.status === 'on') {
      rememberDismissal(userId); setStage(attempted ? 'done' : 'closed')
    }
  }, [attempted, stage, state.status, userId])
  const close = () => { rememberDismissal(userId); setStage('closed') }
  if (stage === 'pending' || stage === 'closed') return null
  if (stage === 'help') return <NotificationPushHelp onClose={close} />
  if (stage === 'done') return <NotificationDialog key="complete" title="알림 설정이 완료됐어요" onClose={close}>
    <p>이 기기에서도 새 공고와 신청한 공고의 일정 알림을 받아요.</p>
    <Button className={styles.primary} onClick={close}>확인</Button>
  </NotificationDialog>
  const busy = state.status === 'busy' || !help && state.status === 'loading'
  const enable = () => {
    rememberDismissal(userId)
    if (help || 'Notification' in window && Notification.permission === 'denied') { setStage('help'); return }
    setAttempted(true)
    // Call synchronously from the button so permission retains browser user activation.
    void context?.client?.enable()
  }
  return <NotificationDialog title="이 기기에서도 알림을 받을까요?" onClose={close}>
    <p>등록한 관심 단지·지역에 새 공고가 올라오면 알려드려요.</p>
    <div className={styles.note}>관심 알림은 다시 신청하지 않아도 돼요.<br />알림 설정에서 언제든 끌 수 있어요.</div>
    {(state.message || context?.error) && <p role={state.status === 'error' || context?.error ? 'alert' : 'status'}>{context?.error || state.message}</p>}
    <div className={styles.actions}>
      <Button className={styles.secondary} onClick={close}>나중에</Button>
      {state.status === 'error' || context?.error ? <Button className={styles.primary} onClick={context?.retry}>다시 시도</Button>
        : <Button className={styles.primary} disabled={busy} onClick={enable}>{busy ? '설정 중…' : '이 기기에서 알림 받기'}</Button>}
    </div>
  </NotificationDialog>
}
