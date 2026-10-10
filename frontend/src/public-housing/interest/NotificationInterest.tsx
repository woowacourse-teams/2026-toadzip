import { Link } from 'react-router'
import { analyticsCollectionAllowed, consentStore } from '../../privacy/consentStore'
import { usePrivacyNotices } from '../../privacy/usePrivacy'
import { captureProductEvent, setProductAuthState, setReplaySensitive } from '../../analytics/productAnalytics'
import { type KeyboardEvent, type ReactNode, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { notificationInterestRepository, NotificationRequestError, type NotificationEventSource, type NotificationEventType, type NotificationInterestEvent, type NotificationInterestRepository, type NotificationInterestResult, type NotificationTarget } from './notificationInterestRepository'
import { getCurrentUser } from '../../user/auth/api'
import { UserSessionControl } from '../../user/auth/UserSessionControl'
import { LoginModal } from '../../user/auth/LoginModal'
import { findRegionBoundaryName } from '../regions/regionBoundaryCatalog'
import styles from './NotificationInterest.module.css'

import { InterestContext, notificationPreparationTitle, notificationPreparationDescription, notificationPreparationNotice, type Selection } from './NotificationInterestContext'

interface Action extends Selection {
  readonly event: NotificationInterestEvent
  readonly authState: 'member'
  readonly pageName: 'explorer' | 'notification_settings'
}

function captureCompletion(action: Action, result: NotificationInterestResult | void) {
  const properties = { target_type: action.target.type, target_id: action.target.id,
    source: action.source, notification_action_id: action.event.eventId }
  if (result?.outcome === 'ACTIVATED' && action.event.eventType === 'CONFIRMED') {
    captureProductEvent('notification_preregistration_completed', {
      ...properties, completion_source: action.event.eventType, server_event_id: result.eventId,
      occurred_at: result.occurredAt,
    }, { dedupeKey: `notification-completed:${result.eventId}`, authState: action.authState, pageName: action.pageName })
  }
  if (result?.outcome === 'CANCELLED' && action.event.eventType === 'CANCELLED') {
    captureProductEvent('notification_cancel_completed', { ...properties, server_event_id: result.eventId },
      { dedupeKey: `notification-cancelled:${result.eventId}`, authState: action.authState, pageName: action.pageName })
  }
}

function captureFailure(action: Action) {
  captureProductEvent(action.event.eventType === 'CANCELLED' ? 'notification_cancel_failed' : 'notification_preregistration_failed',
    { target_type: action.target.type, target_id: action.target.id, source: action.source,
      notification_action_id: action.event.eventId, failure_reason: 'request_failed' },
    { authState: action.authState, pageName: action.pageName })
}

function isApplied(action: Action, result: NotificationInterestResult | void) {
  if (!result?.currentTarget || result.settingsRevision === undefined) return false
  return action.event.eventType === 'CANCELLED'
    ? result.outcome === 'CANCELLED' || result.outcome === 'UNCHANGED'
    : result.outcome === 'ACTIVATED' || result.outcome === 'ALREADY_ACTIVE'
}

export function NotificationInterestSessionControl({ presentation }: { readonly presentation?: 'default' | 'rail' }) {
  const context = useContext(InterestContext)
  return <UserSessionControl onLogout={context?.resetSession} presentation={presentation}
    sessionOverride={context?.mode === 'member' ? 'signed-in' : context?.mode}
    onSessionRetry={context?.refreshStatus} />
}

function requestedKey(target: Pick<NotificationTarget, 'type' | 'id'>) { return `${target.type}:${target.id}` }
function readStorage(kind: 'localStorage' | 'sessionStorage', key: string) {
  try { return window[kind].getItem(key) } catch { return null }
}
function writeStorage(kind: 'localStorage' | 'sessionStorage', key: string, value: string) {
  try { window[kind].setItem(key, value) } catch { /* Memory state remains available. */ }
}

export function NotificationInterestProvider({ children, repository = notificationInterestRepository, loadUser = getCurrentUser }: {
  readonly children: ReactNode
  readonly repository?: NotificationInterestRepository
  readonly loadUser?: typeof getCurrentUser
}) {
  const { notices } = usePrivacyNotices()
  const noticeVersion = notices.find(notice => notice.key === 'NOTIFICATION_NOTICE')?.version
  const memberId = useRef<string | undefined>(undefined)
  const revision = useRef(0)
  const [selection, setSelection] = useState<Selection | null>(null)
  const session = useRef<string | null>(null)
  const [dialog, setDialog] = useState<'guest' | 'confirm' | 'prepared' | 'login' | null>(null)
  const [busy, setBusy] = useState(false)
  const [clearingAll, setClearingAll] = useState(false)
  const [batchError, setBatchError] = useState('')
  const batchRetries = useRef(new Map<string, NotificationInterestEvent>())
  const [failed, setFailed] = useState<Action | null>(null)
  const [message, setMessage] = useState('')
  const [targets, setTargets] = useState<readonly NotificationTarget[]>([])
  const [mode, setMode] = useState<'loading' | 'guest' | 'member' | 'error'>('loading')
  const inFlight = useRef(false)
  const batchInFlight = useRef(false)
  const batchController = useRef<AbortController | null>(null)
  const requestVersion = useRef(0)
  const trigger = useRef<HTMLButtonElement | null>(null)
  const restoreFocus = useRef(false)
  const exposed = useRef(new Set<string>())
  const requested = useMemo(() => new Map(targets.map((target) => [requestedKey(target), true])), [targets])

  const reconcileStatus = useCallback((preserveBatchRetries = false) => {
    if (inFlight.current) return
    const version = ++requestVersion.current
    const statusRequest = repository.loadStatus
      ? repository.loadStatus()
      : loadUser().then((user) => ({ guest: !user, userId: user ? String(user.id) : undefined, settingsRevision: 0, targets: [] }))
    void statusRequest.then((status) => {
      if (version !== requestVersion.current) return
      const member = status && !status.guest
      const nextMemberId = status?.userId
      if (memberId.current !== nextMemberId) {
        if (memberId.current !== undefined) { consentStore.transition(); void consentStore.refresh() }
        setDialog(null); setFailed(null); setSelection(null)
      }
      memberId.current = nextMemberId
      revision.current = status?.settingsRevision ?? 0
      // Automatic reconciliation preserves only unanswered writes for targets
      // still active on the server. A completed UNKNOWN command needs a new ID.
      if (preserveBatchRetries && member) {
        const active = new Set(status.targets.map((target) => `${target.targetType}:${target.targetId}`))
        for (const key of batchRetries.current.keys()) {
          if (!active.has(key)) batchRetries.current.delete(key)
        }
      } else {
        batchRetries.current.clear()
      }
      setBatchError(batchRetries.current.size > 0
        ? `${batchRetries.current.size}개 설정을 해제하지 못했어요. 전체 해제를 눌러 다시 시도해 주세요.` : '')
      setMode(member ? 'member' : 'guest')
      setProductAuthState(member ? 'member' : 'guest')
      setTargets(member ? status.targets.map((target) => ({
        type: target.targetType, id: target.targetId,
        name: (target.targetType === 'REGION' ? findRegionBoundaryName(target.targetId) : target.targetName)
          ?? `${{ REGION: '지역', COMPLEX: '단지', ANNOUNCEMENT: '공고' }[target.targetType]} ${target.targetId}`,
      })) : [])
      if (!member) { setDialog(null); setFailed(null); setMessage('') }
    }).catch(() => {
      if (version === requestVersion.current) { setMode('error'); setTargets([]); setProductAuthState('unknown') }
    })
  }, [loadUser, repository])
  const refreshStatus = useCallback(() => reconcileStatus(), [reconcileStatus])

  const resetSession = useCallback(() => {
    memberId.current = undefined
    requestVersion.current++
    inFlight.current = false
    batchInFlight.current = false
    batchController.current?.abort()
    batchController.current = null
    restoreFocus.current = false
    batchRetries.current.clear()
    setClearingAll(false); setBatchError('')
    setBusy(false); setFailed(null); setDialog(null); setMessage(''); setTargets([]); setMode('loading')
    setProductAuthState('unknown')
    refreshStatus()
  }, [refreshStatus])

  useEffect(() => {
    const version = requestVersion
    const interruptBatch = () => {
      if (!batchInFlight.current) return
      version.current++
      batchInFlight.current = false
      batchController.current?.abort()
      batchController.current = null
      inFlight.current = false
      batchRetries.current.clear()
      setBusy(false); setClearingAll(false); setBatchError('')
      setTargets([]); setMode('loading'); setProductAuthState('unknown')
      setMessage('전체 해제가 중단되었어요. 남은 알림 설정을 확인해 주세요.')
    }
    const visibilityChanged = () => {
      if (document.hidden) interruptBatch()
      else refreshStatus()
    }
    resetSession()
    window.addEventListener('focus', refreshStatus)
    window.addEventListener('blur', interruptBatch)
    document.addEventListener('visibilitychange', visibilityChanged)
    return () => {
      version.current++
      batchController.current?.abort()
      window.removeEventListener('focus', refreshStatus)
      window.removeEventListener('blur', interruptBatch)
      document.removeEventListener('visibilitychange', visibilityChanged)
    }
  }, [refreshStatus, resetSession])

  useEffect(() => {
    if (!dialog && !busy && !failed && restoreFocus.current) {
      restoreFocus.current = false
      trigger.current?.focus({ preventScroll: true })
    }
  }, [busy, failed, dialog])

  useEffect(() => {
    if (!message) return
    const timer = window.setTimeout(() => setMessage(''), 5000)
    return () => window.clearTimeout(timer)
  }, [message])

  const eventFor = useCallback((selection: Selection, eventType: NotificationEventType): NotificationInterestEvent => ({
    eventId: crypto.randomUUID(), eventType, source: selection.source,
    ...(eventType === 'CONFIRMED' || eventType === 'CANCELLED'
      ? { expectedUserId: memberId.current, expectedSettingsRevision: revision.current, ...(eventType === 'CONFIRMED' ? { noticeVersion } : {}) }
      : { sessionId: session.current ?? undefined }),
    targetType: selection.target.type, targetId: selection.target.id,
  }), [noticeVersion])

  const send = useCallback(async (action: Action) => {
    if (inFlight.current) return
    const version = ++requestVersion.current
    const privacyGeneration = consentStore.generation()
    inFlight.current = true
    setBusy(true); setFailed(null); setMessage(''); setBatchError('')
    let refreshAfterResponse = false
    try {
      const result = await repository.record(action.event)
      if (version !== requestVersion.current) return
      if (privacyGeneration !== consentStore.generation() || action.event.expectedUserId !== memberId.current) { refreshAfterResponse = true; return }
      captureCompletion(action, result)
      if (result?.settingsRevision !== undefined) revision.current = result.settingsRevision
      if (!isApplied(action, result)) {
        refreshAfterResponse = true
        restoreFocus.current = true
        setMessage('처리 결과를 확인하지 못했어요. 현재 알림 설정을 다시 확인해 주세요.')
        return
      }
      batchRetries.current.delete(requestedKey(action.target))
      const cancelled = result?.currentTarget ? !result.currentTarget.active : action.event.eventType === 'CANCELLED'
      setTargets((current) => {
        const remaining = current.filter((target) => requestedKey(target) !== requestedKey(action.target))
        return cancelled ? remaining : [...remaining, action.target]
      })
      if (cancelled !== (action.event.eventType === 'CANCELLED')) {
        restoreFocus.current = true
        setMessage('현재 서버의 알림 설정을 반영했어요.')
      } else if (cancelled) {
        restoreFocus.current = true
        setMessage(`${action.target.name} 알림 설정을 해제했어요.`)
      } else {
        setDialog('prepared')
        setMessage(`${action.target.name} 알림 설정을 저장했어요.`)
      }
    } catch (error) {
      if (version !== requestVersion.current) return
      if (privacyGeneration !== consentStore.generation() || action.event.expectedUserId !== memberId.current) { refreshAfterResponse = true; return }
      captureFailure(action)
      if (error instanceof NotificationRequestError && [401, 409].includes(error.status)) {
        refreshAfterResponse = true
        setDialog(null)
        setMessage('설정이 변경됐어요. 현재 상태를 확인하고 다시 선택해 주세요.')
      } else setFailed(action)
    } finally {
      if (version === requestVersion.current) {
        inFlight.current = false; setBusy(false)
        if (refreshAfterResponse) refreshStatus()
      }
    }
  }, [refreshStatus, repository])

  const request = useCallback((selection: Selection, button: HTMLButtonElement) => {
    if (inFlight.current || dialog || failed || mode === 'loading' || mode === 'error') return
    trigger.current = button
    const isRequested = requested.has(requestedKey(selection.target))
    captureProductEvent('notification_cta_clicked', { target_type: selection.target.type, target_id: selection.target.id,
      source: selection.source, action: isRequested ? 'cancel' : 'subscribe' })
    if (mode === 'guest') { setDialog('guest'); return }
    if (!isRequested) { setSelection(selection); setDialog('confirm'); return }
    if (isRequested) captureProductEvent('notification_cancel_requested', {
      target_type: selection.target.type, target_id: selection.target.id, source: selection.source,
    })
    void send({ ...selection, event: eventFor(selection, isRequested ? 'CANCELLED' : 'CONFIRMED'), authState: mode,
      pageName: window.location.pathname === '/mypage/notifications' ? 'notification_settings' : 'explorer' })
  }, [dialog, eventFor, failed, mode, requested, send])

  const clearAll = useCallback(async (button: HTMLButtonElement) => {
    if (mode !== 'member' || inFlight.current || dialog || failed || targets.length === 0) return
    const version = ++requestVersion.current
    const privacyGeneration = consentStore.generation()
    trigger.current = button
    inFlight.current = true
    batchInFlight.current = true
    const controller = new AbortController()
    batchController.current = controller
    setBusy(true); setClearingAll(true); setBatchError(''); setMessage('')
    const pageName = window.location.pathname === '/mypage/notifications' ? 'notification_settings' : 'explorer'
    let failures = 0
    let refreshAfterResponse = false
    for (const target of targets) {
      if (version !== requestVersion.current) return
      const key = requestedKey(target)
      const event = batchRetries.current.get(key) ?? eventFor({ target, source: 'SETTING' }, 'CANCELLED')
      batchRetries.current.set(key, event)
      const action: Action = { target, source: 'SETTING', event, authState: mode, pageName }
      captureProductEvent('notification_cancel_requested', { target_type: target.type, target_id: target.id, source: 'SETTING' })
      try {
        const result = await repository.record(event, controller.signal)
        if (version !== requestVersion.current) return
        if (privacyGeneration !== consentStore.generation() || action.event.expectedUserId !== memberId.current) { refreshAfterResponse = true; break }
        captureCompletion(action, result)
        if (result?.settingsRevision !== undefined) revision.current = result.settingsRevision
        if (!isApplied(action, result)) {
          batchRetries.current.delete(key)
          refreshAfterResponse = true
          continue
        }
        batchRetries.current.delete(key)
        if (result?.currentTarget?.active) { refreshAfterResponse = true; continue }
        setTargets((current) => current.filter((item) => requestedKey(item) !== key))
      } catch (error) {
        if (version !== requestVersion.current) return
        if (privacyGeneration !== consentStore.generation() || action.event.expectedUserId !== memberId.current) { refreshAfterResponse = true; break }
        captureFailure(action)
        failures++
        if (error instanceof NotificationRequestError && [401, 409].includes(error.status)) { batchRetries.current.delete(key); refreshAfterResponse = true; break }
        // An unknown write may have advanced the revision. Stop until its identical retry resolves.
        break
      }
    }
    inFlight.current = false
    batchInFlight.current = false
    batchController.current = null
    setBusy(false); setClearingAll(false)
    if (refreshAfterResponse) {
      setMode('loading')
      setMessage('처리 결과를 확인하지 못했어요. 현재 알림 설정을 다시 확인해 주세요.')
      reconcileStatus(true)
    } else if (failures > 0) {
      setBatchError(`${failures}개 설정을 해제하지 못했어요. 전체 해제를 눌러 다시 시도해 주세요.`)
    } else {
      setMessage('알림 설정을 모두 해제했어요.')
    }
  }, [dialog, eventFor, failed, mode, reconcileStatus, repository, targets])

  const expose = useCallback((selection: Selection) => {
    if (!analyticsCollectionAllowed()) return
    session.current ??= crypto.randomUUID()
    const key = `toadzip.notification-interest.exposed:${selection.source}:${selection.target.type}:${selection.target.id}`
    if (exposed.current.has(key) || readStorage('sessionStorage', key) === '1') return
    const privacyGeneration = consentStore.generation()
    exposed.current.add(key)
    captureProductEvent('notification_cta_viewed', { target_type: selection.target.type, target_id: selection.target.id, source: selection.source },
      { dedupeKey: `${session.current}:${key}` })
    void repository.record(eventFor(selection, 'EXPOSED')).then((result) => {
      if (!result || privacyGeneration !== consentStore.generation() || !analyticsCollectionAllowed()) return
      writeStorage('sessionStorage', key, '1')
    }).catch(() => { exposed.current.delete(key) })
  }, [eventFor, repository])

  const context = useMemo(() => ({ blocked: mode === 'loading' || mode === 'error' || busy || dialog !== null || failed !== null,
    mode, requested, targets, request, expose, resetSession, refreshStatus, clearAll, clearingAll, batchError }), [clearAll, clearingAll, batchError, busy, dialog, expose, failed, mode, request, requested, targets, resetSession, refreshStatus])
  function dismiss() { if (busy) return; restoreFocus.current = true; setDialog(null); setFailed(null); setSelection(null) }

  return <InterestContext.Provider value={context}>
    {children}
    {dialog === 'guest' && <InterestDialog onDismiss={dismiss} label="로그인이 필요해요">
      <h2>로그인이 필요해요</h2><p className={styles.description}>로그인한 사용자만 이용할 수 있어요.</p>
      <div className={styles.actions}>
        <button className={styles.submit} type="button" onClick={() => setDialog('login')}>로그인</button>
        <button className={styles.cancel} type="button" onClick={dismiss}>닫기</button>
      </div>
    </InterestDialog>}
    {dialog === 'login' && <LoginModal loginFailed={false} sessionError={false} onClose={dismiss} returnFocusRef={trigger} />}
    {dialog === 'confirm' && selection && <InterestDialog onDismiss={dismiss} label={`${selection.target.type === 'REGION' ? '지역' : selection.target.type === 'ANNOUNCEMENT' ? '공고' : '단지'} 알림을 신청할까요?`}>
      <h2>{selection.target.type === 'REGION' ? '지역' : selection.target.type === 'ANNOUNCEMENT' ? '공고' : '단지'} 알림을 신청할까요?</h2>
      <p className={styles.description}>{selection.target.name}의 새 공고를 확인할 수 있도록 관심 대상으로 저장해요.</p>
      <div className={styles.target}><strong>알림 기능은 현재 준비 중이에요.</strong><p className={styles.hint}>{notificationPreparationNotice}</p></div>
      <div className={styles.actions}>
        <button className={styles.cancel} type="button" disabled={busy} onClick={dismiss}>취소</button>
        <button className={styles.submit} type="button" disabled={busy || !noticeVersion} onClick={() => { const action: Action = { ...selection, event: eventFor(selection, 'CONFIRMED'), authState: 'member', pageName: window.location.pathname === '/mypage/notifications' ? 'notification_settings' : 'explorer' }; setDialog(null); void send(action) }}>신청하기</button>
      </div>
      <p className={styles.hint}>알림 설정 저장에 이용자 ID와 선택한 대상을 사용해요.</p>
      <p className={styles.hint}>이 설정은 회원 계정에 연결해 저장돼요.</p>
      <Link className={styles.helpLink} to="/privacy">개인정보처리방침</Link>
      {!noticeVersion && <p className={styles.hint} role="status">신청 안내를 확인하고 있어요.</p>}
    </InterestDialog>}
    {dialog === 'prepared' && <InterestDialog onDismiss={dismiss}>
      <h2 id="notification-interest-title">{notificationPreparationTitle}</h2>
      <p className={styles.description}>{notificationPreparationDescription}</p>
      <p className={styles.description}>{notificationPreparationNotice}</p>
      <p className={styles.hint} role="status">{message}</p>
      <div className={styles.actions}><button className={styles.submit} type="button" onClick={dismiss}>확인</button></div>
    </InterestDialog>}
    {failed && <InterestDialog label="알림 요청 오류" onDismiss={dismiss}>
      <div className={styles.error} role="alert"><p>{failed.event.eventType === 'CANCELLED'
        ? '알림 해제를 완료하지 못했어요. 다시 시도해 주세요.' : '알림 설정을 저장하지 못했어요. 다시 시도해 주세요.'}</p></div>
      <div className={styles.actions}>
        <button className={styles.submit} type="button" onClick={() => void send(failed)}>다시 시도</button>
        <button className={styles.cancel} type="button" onClick={dismiss}>닫기</button>
      </div>
    </InterestDialog>}
    {!dialog && !failed && message && <div className={styles.feedback}><p role="status">{message}</p></div>}
    {mode === 'error' && <div className={styles.feedback} role="alert">
      <p>알림 상태를 불러오지 못했어요.</p><button type="button" onClick={refreshStatus}>다시 시도</button>
    </div>}
  </InterestContext.Provider>
}

function InterestDialog({ children, onDismiss, label }: { readonly children: ReactNode; readonly onDismiss: () => void; readonly label?: string }) {
  const panel = useRef<HTMLDialogElement>(null)
  useLayoutEffect(() => {
    const element = panel.current
    setReplaySensitive('notification_form', true)
    element?.showModal()
    element?.querySelector<HTMLElement>('input:not(:disabled), button:not(:disabled)')?.focus()
    return () => { element?.close(); setReplaySensitive('notification_form', false) }
  }, [])

  function keyDown(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onDismiss() }
    if (event.key !== 'Tab') return
    const controls = Array.from(panel.current?.querySelectorAll<HTMLElement>('input:not(:disabled), button:not(:disabled), a[href]') ?? [])
    const first = controls[0]
    const last = controls.at(-1)
    if (!first || !last) { event.preventDefault(); return }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }

  return (
      <dialog ref={panel} className={`${styles.dialog} ph-no-capture`} aria-modal="true" aria-label={label} aria-labelledby={label ? undefined : 'notification-interest-title'} onKeyDown={keyDown}
        onCancel={(event) => { event.preventDefault(); event.stopPropagation(); onDismiss() }}>
        {children}
      </dialog>
  )
}

export function NotificationInterestButton({ target, source, iconOnly = false }: {
  readonly target: NotificationTarget
  readonly source: NotificationEventSource
  readonly iconOnly?: boolean
}) {
  const context = useContext(InterestContext)
  const button = useRef<HTMLButtonElement>(null)
  const expose = context?.expose
  const { type, id, name } = target
  const key = requestedKey(target)
  const isRequested = context?.requested.get(key) ?? false
  useEffect(() => {
    const element = button.current
    if (!element || !expose || typeof IntersectionObserver === 'undefined') return
    let intersecting = false
    let done = false
    const check = () => {
      if (done || !intersecting || document.visibilityState !== 'visible' || element.closest('[inert], [hidden]')) return
      const modals = Array.from(document.querySelectorAll<HTMLDialogElement>('dialog[open]'))
      if (modals.some((modal) => !modal.contains(element))) return
      done = true
      expose({ target: { type, id, name }, source })
    }
    const observer = new IntersectionObserver((entries) => {
      intersecting = entries.some((entry) => entry.isIntersecting)
      check()
    })
    const mutations = new MutationObserver(check)
    mutations.observe(document.body, { subtree: true, attributes: true, attributeFilter: ['open', 'hidden', 'inert'], childList: true })
    document.addEventListener('visibilitychange', check)
    observer.observe(element)
    return () => { observer.disconnect(); mutations.disconnect(); document.removeEventListener('visibilitychange', check) }
  }, [expose, id, name, source, type])

  if (!context) return null
  return (
    <button ref={button} type="button" className={iconOnly ? styles.bell : styles.button}
      aria-label={`${name} 알림 ${isRequested ? '취소' : '받기'}`} title={iconOnly ? (isRequested ? '알림 취소' : '알림 받기') : undefined}
      aria-pressed={isRequested} data-requested={isRequested} disabled={context.blocked}
      onClick={(event) => context.request({ target, source }, event.currentTarget)}>
      <svg aria-hidden="true" data-state={isRequested ? 'requested' : 'idle'} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" fill={isRequested ? 'currentColor' : 'none'} />
        <path d="M10 21h4" />
      </svg>
      {!iconOnly && (isRequested ? '알림 취소' : '알림 받기')}
    </button>
  )
}
