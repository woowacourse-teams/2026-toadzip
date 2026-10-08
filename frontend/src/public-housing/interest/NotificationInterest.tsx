import { captureProductEvent, setProductAuthState, setReplaySensitive } from '../../analytics/productAnalytics'
import { createContext, type KeyboardEvent, type ReactNode, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { notificationInterestRepository, type NotificationEventSource, type NotificationEventType, type NotificationInterestEvent, type NotificationInterestRepository, type NotificationTarget } from './notificationInterestRepository'
import { getCurrentUser } from '../../user/auth/api'
import { UserSessionControl } from '../../user/auth/UserSessionControl'
import styles from './NotificationInterest.module.css'

const sessionKey = 'toadzip.notification-interest.session'
const clientKey = 'toadzip.notification-interest.client'
const emailPromptKey = 'toadzip.notification-interest.email-confirmed'
const requestedKeyPrefix = 'toadzip.notification-interest.requested'

interface Selection {
  readonly target: NotificationTarget
  readonly source: NotificationEventSource
}

interface Action extends Selection {
  readonly event: NotificationInterestEvent
  readonly next: 'prompt' | 'complete' | 'finish' | 'cancel'
  readonly authState: 'member' | 'guest' | 'unknown'
}

const InterestContext = createContext<{
  readonly blocked: boolean
  readonly mode: 'loading' | 'guest' | 'member' | 'error'
  readonly serverStatus: boolean
  readonly requested: ReadonlyMap<string, boolean>
  readonly resetSession: () => void
  readonly request: (selection: Selection, trigger: HTMLButtonElement) => void
  readonly expose: (selection: Selection) => void
} | null>(null)

export function NotificationInterestSessionControl({ presentation }: { readonly presentation?: 'default' | 'rail' }) {
  const context = useContext(InterestContext)
  return <UserSessionControl onLogout={context?.resetSession} presentation={presentation} />
}

function requestedKey(target: Pick<NotificationTarget, 'type' | 'id'>) {
  return `${requestedKeyPrefix}:${target.type}:${target.id}`
}

function readStorage(kind: 'localStorage' | 'sessionStorage', key: string) {
  try { return window[kind].getItem(key) } catch { return null }
}

function writeStorage(kind: 'localStorage' | 'sessionStorage', key: string, value: string) {
  try { window[kind].setItem(key, value) } catch { /* The mounted provider keeps a memory fallback. */ }
}

function sessionId() {
  const stored = readStorage('sessionStorage', sessionKey)
  if (stored && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(stored)) return stored
  const id = crypto.randomUUID()
  writeStorage('sessionStorage', sessionKey, id)
  return id
}

function clientId() {
  const stored = readStorage('localStorage', clientKey)
  if (stored && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(stored)) return stored
  const id = crypto.randomUUID()
  writeStorage('localStorage', clientKey, id)
  return id
}

export function NotificationInterestProvider({ children, repository = notificationInterestRepository, loadUser = getCurrentUser }: {
  readonly children: ReactNode
  readonly repository?: NotificationInterestRepository
  readonly loadUser?: typeof getCurrentUser
}) {
  const [session] = useState(sessionId)
  const [client] = useState(clientId)
  const [prompt, setPrompt] = useState<(Selection & { readonly actionId: string }) | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<Action | null>(null)
  const [message, setMessage] = useState('')
  const [email, setEmail] = useState('')
  const [loadingUser, setLoadingUser] = useState(false)
  const [requested, setRequested] = useState<ReadonlyMap<string, boolean>>(() => new Map())
  const [mode, setMode] = useState<'loading' | 'guest' | 'member' | 'error'>('loading')
  const [serverStatus, setServerStatus] = useState(false)
  const inFlight = useRef(false)
  const requestVersion = useRef(0)
  const completed = useRef(false)
  const trigger = useRef<HTMLButtonElement | null>(null)
  const restoreFocus = useRef(false)
  const exposed = useRef(new Set<string>())

  const refreshStatus = useCallback(() => {
    if (!repository.loadStatus || inFlight.current) {
      if (!repository.loadStatus) setMode('guest')
      return
    }
    const request = ++requestVersion.current
    void repository.loadStatus(client).then((status) => {
      if (request !== requestVersion.current) return
      if (!status) {
        completed.current = false
        setRequested(new Map())
        setServerStatus(false)
        setMode('guest')
        setProductAuthState('guest')
        return
      }
      completed.current = status.emailConfirmed
      setRequested(new Map(status.targets.map((target) => [requestedKey({ type: target.targetType, id: target.targetId }), true])))
      setServerStatus(true)
      setMode(status.guest ? 'guest' : 'member')
      setProductAuthState(status.guest ? 'guest' : 'member')
    }).catch(() => {
      if (request === requestVersion.current) setMode((current) => current === 'loading' ? 'error' : current)
    })
  }, [client, repository])

  const resetSession = useCallback(() => {
    requestVersion.current++
    inFlight.current = false
    completed.current = false
    restoreFocus.current = false
    setBusy(false)
    setFailed(null)
    setPrompt(null)
    setEmail('')
    setMessage('')
    setRequested(new Map())
    setServerStatus(false)
    setMode('loading')
    setProductAuthState('unknown')
    refreshStatus()
  }, [refreshStatus])

  useEffect(() => {
    const requestCounter = requestVersion
    resetSession()
    window.addEventListener('focus', refreshStatus)
    return () => { requestCounter.current++; window.removeEventListener('focus', refreshStatus) }
  }, [refreshStatus, resetSession])

  useEffect(() => {
    if (!prompt && !busy && !failed && restoreFocus.current) {
      restoreFocus.current = false
      trigger.current?.focus({ preventScroll: true })
    }
  }, [busy, failed, prompt])

  useEffect(() => {
    if (!prompt) return
    let active = true
    setLoadingUser(true)
    void loadUser().then((user) => {
      if (active && user?.email) setEmail((current) => current || user.email || '')
    }).catch(() => {
      // Email can still be entered when the session lookup is unavailable.
    }).finally(() => {
      if (active) setLoadingUser(false)
    })
    return () => { active = false }
  }, [loadUser, prompt])

  useEffect(() => {
    if (!message) return
    const timer = window.setTimeout(() => setMessage(''), 5000)
    return () => window.clearTimeout(timer)
  }, [message])

  const eventFor = useCallback((selection: Selection, eventType: NotificationEventType): NotificationInterestEvent => ({
    eventId: crypto.randomUUID(), sessionId: session, clientId: client, eventType, source: selection.source,
    targetType: selection.target.type, targetId: selection.target.id,
  }), [client, session])

  const send = useCallback(async (action: Action) => {
    if (inFlight.current) return
    const request = ++requestVersion.current
    inFlight.current = true
    setBusy(true)
    setFailed(null)
    setMessage('')
    let refreshAfterResponse = false
    try {
      const result = await repository.record(action.event)
      const properties = { target_type: action.target.type, target_id: action.target.id,
        source: action.source, notification_action_id: action.event.eventId }
      // A committed result remains a real event after the initiating UI closes.
      // Snapshot the request context, while keeping all late UI changes guarded.
      if (result?.outcome === 'ACTIVATED' && ['CLICKED', 'CONFIRMED'].includes(action.event.eventType)) {
        captureProductEvent('notification_preregistration_completed', {
          ...properties, completion_source: action.event.eventType, server_event_id: result.eventId,
          occurred_at: result.occurredAt,
        }, { dedupeKey: `notification-completed:${result.eventId}`, authState: action.authState, pageName: 'explorer' })
      }
      if (result?.outcome === 'CANCELLED' && action.event.eventType === 'CANCELLED') {
        captureProductEvent('notification_cancel_completed', { ...properties, server_event_id: result.eventId },
          { dedupeKey: `notification-cancelled:${result.eventId}`, authState: action.authState, pageName: 'explorer' })
      }
      if (request !== requestVersion.current) return
      let next = action.next
      if (result && (action.event.eventType === 'CLICKED' || action.event.eventType === 'CONFIRMED')) {
        if (result.outcome === 'ACTIVATED' || result.outcome === 'ALREADY_ACTIVE') {
          next = action.event.eventType === 'CONFIRMED' ? 'complete' : 'finish'
        } else if (result.outcome === 'NOT_ACTIVATED') {
          // Another tab may have removed the last subscription and its email.
          // The old local email flag must not turn this into a successful signup.
          completed.current = false
          const key = requestedKey(action.target)
          if (mode === 'guest') {
            writeStorage('localStorage', emailPromptKey, '0')
            writeStorage('localStorage', key, '0')
          }
          setRequested((current) => new Map(current).set(key, false))
          next = 'prompt'
        } else {
          refreshAfterResponse = true
          restoreFocus.current = true
          setPrompt(null)
          setMessage('처리 결과를 확인하지 못했어요. 현재 신청 상태를 다시 확인해 주세요.')
          return
        }
      }
      if (result && action.event.eventType === 'CANCELLED'
        && result.outcome !== 'CANCELLED' && result.outcome !== 'UNCHANGED') {
        refreshAfterResponse = true
        restoreFocus.current = true
        setMessage('처리 결과를 확인하지 못했어요. 현재 신청 상태를 다시 확인해 주세요.')
        return
      }
      if (next === 'prompt') {
        setReplaySensitive('notification_form', true)
        setEmail('')
        setPrompt({ target: action.target, source: action.source, actionId: action.event.eventId })
      } else if (next === 'complete') {
        if (action.event.eventType === 'CONFIRMED') {
          completed.current = true
          if (mode === 'guest') writeStorage('localStorage', emailPromptKey, '1')
          const key = requestedKey(action.target)
          if (mode === 'guest') writeStorage('localStorage', key, '1')
          setRequested((current) => new Map(current).set(key, true))
        }
        restoreFocus.current = true
        setPrompt(null)
        setMessage(action.event.eventType === 'DECLINED'
          ? '알림 신청을 하지 않았어요. 언제든 다시 신청할 수 있어요.'
          : `${action.target.name} 알림 신청을 받았어요.`)
      } else if (next === 'finish') {
        completed.current = true
        if (mode === 'guest') writeStorage('localStorage', emailPromptKey, '1')
        const key = requestedKey(action.target)
        if (mode === 'guest') writeStorage('localStorage', key, '1')
        setRequested((current) => new Map(current).set(key, true))
        restoreFocus.current = true
        setMessage(`${action.target.name} 알림 신청을 받았어요.`)
      } else {
        const key = requestedKey(action.target)
        if (mode === 'guest') writeStorage('localStorage', key, '0')
        if (![...requested].some(([targetKey, active]) => targetKey !== key && active)) {
          completed.current = false
          if (mode === 'guest') writeStorage('localStorage', emailPromptKey, '0')
        }
        setRequested((current) => new Map(current).set(key, false))
        restoreFocus.current = true
        setMessage(result?.outcome === 'UNCHANGED'
          ? '현재 신청된 알림이 없어요.'
          : `${action.target.name} 알림 신청을 취소했어요.`)
      }
    } catch {
      if (action.event.eventType !== 'DECLINED') captureProductEvent(
        action.event.eventType === 'CANCELLED' ? 'notification_cancel_failed' : 'notification_preregistration_failed',
        { target_type: action.target.type, target_id: action.target.id, source: action.source,
          notification_action_id: action.event.eventId, failure_reason: 'request_failed' },
        { authState: action.authState, pageName: 'explorer' })
      if (request === requestVersion.current) setFailed(action)
    } finally {
      if (request === requestVersion.current) {
        inFlight.current = false
        setBusy(false)
        if (refreshAfterResponse) refreshStatus()
      }
    }
  }, [mode, refreshStatus, repository, requested])

  const request = useCallback((selection: Selection, button: HTMLButtonElement) => {
    if (inFlight.current || prompt || failed) return
    trigger.current = button
    const authState = mode === 'member' || mode === 'guest' ? mode : 'unknown'
    const key = requestedKey(selection.target)
    const isRequested = requested.get(key) ?? (!serverStatus && mode === 'guest' && readStorage('localStorage', key) === '1')
    captureProductEvent('notification_cta_clicked', { target_type: selection.target.type, target_id: selection.target.id,
      source: selection.source, action: isRequested ? 'cancel' : 'subscribe' })
    if (isRequested) {
      captureProductEvent('notification_cancel_requested', { target_type: selection.target.type, target_id: selection.target.id, source: selection.source })
      void send({ ...selection, event: eventFor(selection, 'CANCELLED'), next: 'cancel', authState })
      return
    }
    const alreadyAsked = completed.current || (!serverStatus && mode === 'guest' && readStorage('localStorage', emailPromptKey) === '1')
    void send({ ...selection, event: eventFor(selection, 'CLICKED'), next: alreadyAsked ? 'finish' : 'prompt', authState })
  }, [eventFor, failed, mode, prompt, requested, send, serverStatus])

  const expose = useCallback((selection: Selection) => {
    const key = `toadzip.notification-interest.exposed:${selection.source}:${selection.target.type}:${selection.target.id}`
    if (exposed.current.has(key) || readStorage('sessionStorage', key) === '1') return
    exposed.current.add(key)
    captureProductEvent('notification_cta_viewed', { target_type: selection.target.type, target_id: selection.target.id, source: selection.source },
      { dedupeKey: `${session}:${key}` })
    void repository.record(eventFor(selection, 'EXPOSED')).then(() => {
      writeStorage('sessionStorage', key, '1')
    }).catch(() => {
      exposed.current.delete(key)
      console.warn('알림 버튼 노출 정보를 기록하지 못했습니다.')
    })
  }, [eventFor, repository, session])

  const context = useMemo(() => ({ blocked: mode === 'loading' || mode === 'error' || busy || prompt !== null || failed !== null, mode, serverStatus, requested, request, expose, resetSession }), [busy, expose, failed, mode, prompt, request, requested, resetSession, serverStatus])
  function clearFailure() {
    restoreFocus.current = true
    setFailed(null)
    if (prompt) {
      captureProductEvent('notification_form_dismissed', { target_type: prompt.target.type, target_id: prompt.target.id, source: prompt.source, reason: 'error_closed' })
      setPrompt(null)
    }
  }
  const error = failed && (
    <div role="alert" className={styles.error}>
      <p>{failed.event.eventType === 'CANCELLED'
        ? '알림 취소를 완료하지 못했어요. 다시 시도해 주세요.'
        : '알림 신청을 완료하지 못했어요. 다시 시도해 주세요.'}</p>
      <button type="button" disabled={busy} onClick={() => { void send(failed) }}>다시 시도</button>
      <button type="button" disabled={busy} onClick={clearFailure}>닫기</button>
    </div>
  )

  function answer(eventType: 'CONFIRMED' | 'DECLINED') {
    if (!prompt || failed || busy) return
    const address = email.trim()
    if (eventType === 'CONFIRMED' && !address) return
    captureProductEvent(eventType === 'CONFIRMED' ? 'notification_form_submitted' : 'notification_form_dismissed',
      { target_type: prompt.target.type, target_id: prompt.target.id, source: prompt.source })
    const event = { ...eventFor(prompt, eventType), ...(eventType === 'CONFIRMED' ? { email: address } : {}) }
    void send({ ...prompt, event, next: 'complete', authState: mode === 'member' || mode === 'guest' ? mode : 'unknown' })
  }

  const previousClickNeedsApplication = prompt && serverStatus && !completed.current
    && readStorage('localStorage', requestedKey(prompt.target)) === '1'

  return (
    <InterestContext.Provider value={context}>
      {children}
      {prompt && (
        <InterestDialog onShown={() => captureProductEvent('notification_form_viewed', {
          target_type: prompt.target.type, target_id: prompt.target.id, source: prompt.source,
          notification_action_id: prompt.actionId,
        }, { dedupeKey: `notification-form:${prompt.actionId}` })}
          onDismiss={() => { if (failed) clearFailure(); else answer('DECLINED') }}>
          <form onSubmit={(event) => { event.preventDefault(); answer('CONFIRMED') }}>
            <span className={styles.eyebrow}>공공주택 복덕방</span>
            <h2 id="notification-interest-title">이메일 알림 신청</h2>
            <p className={styles.description}>{`이 ${{ REGION: '지역', COMPLEX: '단지', ANNOUNCEMENT: '공고' }[prompt.target.type]}에 대한 알림을 받으시겠습니까?`}</p>
            <p className={styles.target}>{prompt.target.name}</p>
            {previousClickNeedsApplication && <p className={styles.hint}>이전에 누른 알림은 현재 신청 목록에 포함되지 않았어요. 이메일을 입력해 다시 신청해 주세요.</p>}
            <label className={styles.label} htmlFor="notification-email">알림 받을 이메일</label>
            <input id="notification-email" className={styles.input} type="email" autoComplete="email"
              placeholder="name@example.com" required maxLength={254} pattern={'[^\\s@]+@[^\\s@]+\\.[^\\s@]+'} value={email}
              onChange={(event) => setEmail(event.target.value)} disabled={busy || failed !== null} />
            {loadingUser && <p className={styles.hint} role="status">로그인 이메일을 확인하는 중…</p>}
            <div className={styles.actions}>
              <button className={styles.submit} type="submit" disabled={busy || failed !== null}>알림 신청</button>
              <button className={styles.cancel} type="button" disabled={busy}
                onClick={() => { if (failed) clearFailure(); else answer('DECLINED') }}>취소</button>
            </div>
            <p className={styles.hint}>신청은 12개월 동안 유지되며, 마지막 알림을 취소하면 알림용 이메일도 삭제됩니다.</p>
            <a className={styles.helpLink} href="/notifications/cancel">브라우저 데이터를 지워 신청을 취소할 수 없나요?</a>
            {busy && <p role="status">알림 신청을 처리하는 중…</p>}
            {error}
          </form>
        </InterestDialog>
      )}
      {!prompt && error && <InterestDialog label="알림 요청 오류" onDismiss={clearFailure}>{error}</InterestDialog>}
      {!prompt && !error && message && <div className={styles.feedback}><p role="status">{message}</p></div>}
      {mode === 'error' && <div className={styles.feedback} role="alert">
        <p>알림 상태를 불러오지 못했어요.</p>
        <button type="button" onClick={refreshStatus}>다시 시도</button>
      </div>}
    </InterestContext.Provider>
  )
}

function InterestDialog({ children, onDismiss, label, onShown }: { readonly children: ReactNode; readonly onDismiss: () => void; readonly label?: string; readonly onShown?: () => void }) {
  const shown = useRef(onShown)
  const panel = useRef<HTMLDialogElement>(null)
  useLayoutEffect(() => {
    const element = panel.current
    setReplaySensitive('notification_form', true)
    element?.showModal()
    if (element?.open) shown.current?.()
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
  const isRequested = context?.requested.get(key) ?? (!context?.serverStatus && context?.mode === 'guest' && readStorage('localStorage', key) === '1')
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
