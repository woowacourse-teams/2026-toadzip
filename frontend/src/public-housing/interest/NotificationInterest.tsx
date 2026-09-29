import { createContext, type KeyboardEvent, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { notificationInterestRepository, type NotificationEventSource, type NotificationEventType, type NotificationInterestEvent, type NotificationInterestRepository, type NotificationTarget } from './notificationInterestRepository'
import { getCurrentUser } from '../../user/auth/api'
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
}

const InterestContext = createContext<{
  readonly blocked: boolean
  readonly mode: 'loading' | 'guest' | 'member' | 'error'
  readonly serverStatus: boolean
  readonly requested: ReadonlyMap<string, boolean>
  readonly request: (selection: Selection, trigger: HTMLButtonElement) => void
  readonly expose: (selection: Selection) => void
} | null>(null)

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
  const [prompt, setPrompt] = useState<Selection | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<Action | null>(null)
  const [message, setMessage] = useState('')
  const [email, setEmail] = useState('')
  const [loadingUser, setLoadingUser] = useState(false)
  const [requested, setRequested] = useState<ReadonlyMap<string, boolean>>(() => new Map())
  const [mode, setMode] = useState<'loading' | 'guest' | 'member' | 'error'>('loading')
  const [serverStatus, setServerStatus] = useState(false)
  const inFlight = useRef(false)
  const statusRequest = useRef(0)
  const completed = useRef(false)
  const trigger = useRef<HTMLButtonElement | null>(null)
  const restoreFocus = useRef(false)
  const exposed = useRef(new Set<string>())

  const refreshStatus = useCallback(() => {
    if (!repository.loadStatus || inFlight.current) {
      if (!repository.loadStatus) setMode('guest')
      return
    }
    const request = ++statusRequest.current
    void repository.loadStatus(client).then((status) => {
      if (request !== statusRequest.current) return
      if (!status) {
        completed.current = false
        setRequested(new Map())
        setServerStatus(false)
        setMode('guest')
        return
      }
      completed.current = status.emailConfirmed
      setRequested(new Map(status.targets.map((target) => [requestedKey({ type: target.targetType, id: target.targetId }), true])))
      setServerStatus(true)
      setMode(status.guest ? 'guest' : 'member')
    }).catch(() => {
      if (request === statusRequest.current) setMode((current) => current === 'loading' ? 'error' : current)
    })
  }, [client, repository])

  useEffect(() => {
    const requestCounter = statusRequest
    refreshStatus()
    window.addEventListener('focus', refreshStatus)
    return () => { requestCounter.current++; window.removeEventListener('focus', refreshStatus) }
  }, [refreshStatus])

  useEffect(() => {
    if (!prompt && !busy && restoreFocus.current) {
      restoreFocus.current = false
      trigger.current?.focus({ preventScroll: true })
    }
  }, [busy, prompt])

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
    inFlight.current = true
    setBusy(true)
    setFailed(null)
    setMessage('')
    try {
      await repository.record(action.event)
      if (action.next === 'prompt') {
        setEmail('')
        setPrompt({ target: action.target, source: action.source })
      } else if (action.next === 'complete') {
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
      } else if (action.next === 'finish') {
        const key = requestedKey(action.target)
        if (mode === 'guest') writeStorage('localStorage', key, '1')
        setRequested((current) => new Map(current).set(key, true))
        restoreFocus.current = true
        setMessage(`${action.target.name} 알림 신청을 받았어요.`)
      } else {
        const key = requestedKey(action.target)
        if (mode === 'guest') writeStorage('localStorage', key, '0')
        setRequested((current) => new Map(current).set(key, false))
        restoreFocus.current = true
        setMessage(`${action.target.name} 알림 신청을 취소했어요.`)
      }
    } catch {
      setFailed(action)
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }, [mode, repository])

  const request = useCallback((selection: Selection, button: HTMLButtonElement) => {
    if (inFlight.current || prompt || failed) return
    trigger.current = button
    const key = requestedKey(selection.target)
    const isRequested = requested.get(key) ?? (!serverStatus && mode === 'guest' && readStorage('localStorage', key) === '1')
    if (isRequested) {
      void send({ ...selection, event: eventFor(selection, 'CANCELLED'), next: 'cancel' })
      return
    }
    const alreadyAsked = completed.current || (!serverStatus && mode === 'guest' && readStorage('localStorage', emailPromptKey) === '1')
    void send({ ...selection, event: eventFor(selection, 'CLICKED'), next: alreadyAsked ? 'finish' : 'prompt' })
  }, [eventFor, failed, mode, prompt, requested, send, serverStatus])

  const expose = useCallback((selection: Selection) => {
    const key = `toadzip.notification-interest.exposed:${selection.source}:${selection.target.type}:${selection.target.id}`
    if (exposed.current.has(key) || readStorage('sessionStorage', key) === '1') return
    exposed.current.add(key)
    void repository.record(eventFor(selection, 'EXPOSED')).then(() => {
      writeStorage('sessionStorage', key, '1')
    }).catch(() => {
      exposed.current.delete(key)
      console.warn('알림 버튼 노출 정보를 기록하지 못했습니다.')
    })
  }, [eventFor, repository])

  const context = useMemo(() => ({ blocked: mode === 'loading' || mode === 'error' || busy || prompt !== null || failed !== null, mode, serverStatus, requested, request, expose }), [busy, expose, failed, mode, prompt, request, requested, serverStatus])
  function clearFailure() {
    setFailed(null)
    if (prompt) {
      restoreFocus.current = true
      setPrompt(null)
    } else {
      trigger.current?.focus({ preventScroll: true })
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
    const event = { ...eventFor(prompt, eventType), ...(eventType === 'CONFIRMED' ? { email: address } : {}) }
    void send({ ...prompt, event, next: 'complete' })
  }

  const previousClickNeedsApplication = prompt && serverStatus && !completed.current
    && readStorage('localStorage', requestedKey(prompt.target)) === '1'

  return (
    <InterestContext.Provider value={context}>
      {children}
      {prompt && (
        <InterestDialog onDismiss={() => { if (failed) clearFailure(); else answer('DECLINED') }}>
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
            {busy && <p role="status">알림 신청을 처리하는 중…</p>}
            {error}
          </form>
        </InterestDialog>
      )}
      {!prompt && (error || message) && (
        <div className={styles.feedback}>{error || <p role="status">{message}</p>}</div>
      )}
      {mode === 'error' && <div className={styles.feedback} role="alert">
        <p>알림 상태를 불러오지 못했어요.</p>
        <button type="button" onClick={refreshStatus}>다시 시도</button>
      </div>}
    </InterestContext.Provider>
  )
}

function InterestDialog({ children, onDismiss }: { readonly children: ReactNode; readonly onDismiss: () => void }) {
  const panel = useRef<HTMLDivElement>(null)
  useEffect(() => { panel.current?.querySelector<HTMLInputElement>('input')?.focus() }, [])

  function keyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onDismiss() }
    if (event.key !== 'Tab') return
    const controls = Array.from(panel.current?.querySelectorAll<HTMLElement>('input:not(:disabled), button:not(:disabled)') ?? [])
    const first = controls[0]
    const last = controls.at(-1)
    if (!first || !last) { event.preventDefault(); return }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }

  return (
    <div className={styles.backdrop}>
      <div ref={panel} className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="notification-interest-title" onKeyDown={keyDown}>
        {children}
      </div>
    </div>
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
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        expose({ target: { type, id, name }, source })
        observer.disconnect()
      }
    })
    observer.observe(element)
    return () => observer.disconnect()
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
