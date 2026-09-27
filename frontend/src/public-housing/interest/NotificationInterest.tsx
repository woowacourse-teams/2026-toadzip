import { createContext, type KeyboardEvent, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { notificationInterestRepository, type NotificationEventSource, type NotificationEventType, type NotificationInterestEvent, type NotificationInterestRepository, type NotificationTarget } from './notificationInterestRepository'
import styles from './NotificationInterest.module.css'

const promptKey = 'toadzip.notification-interest.prompt-completed'
const sessionKey = 'toadzip.notification-interest.session'

interface Selection {
  readonly target: NotificationTarget
  readonly source: NotificationEventSource
}

interface Action extends Selection {
  readonly event: NotificationInterestEvent
  readonly next: 'prompt' | 'complete' | 'finish'
}

const InterestContext = createContext<{
  readonly blocked: boolean
  readonly request: (selection: Selection, trigger: HTMLButtonElement) => void
  readonly expose: (selection: Selection) => void
} | null>(null)

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

export function NotificationInterestProvider({ children, repository = notificationInterestRepository }: {
  readonly children: ReactNode
  readonly repository?: NotificationInterestRepository
}) {
  const [session] = useState(sessionId)
  const [prompt, setPrompt] = useState<Selection | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<Action | null>(null)
  const [message, setMessage] = useState('')
  const inFlight = useRef(false)
  const completed = useRef(false)
  const trigger = useRef<HTMLButtonElement | null>(null)
  const hadPrompt = useRef(false)
  const exposed = useRef(new Set<string>())

  useEffect(() => {
    if (prompt) hadPrompt.current = true
    if (!prompt && !busy && hadPrompt.current) {
      hadPrompt.current = false
      trigger.current?.focus({ preventScroll: true })
    }
  }, [busy, prompt])

  const eventFor = useCallback((selection: Selection, eventType: NotificationEventType): NotificationInterestEvent => ({
    eventId: crypto.randomUUID(), sessionId: session, eventType, source: selection.source,
    targetType: selection.target.type, targetId: selection.target.id,
  }), [session])

  const send = useCallback(async (action: Action) => {
    if (inFlight.current) return
    inFlight.current = true
    setBusy(true)
    setFailed(null)
    setMessage('')
    try {
      await repository.record(action.event)
      if (action.next === 'prompt') {
        setPrompt({ target: action.target, source: action.source })
      } else {
        if (action.next === 'complete') {
          completed.current = true
          writeStorage('localStorage', promptKey, '1')
          setPrompt(null)
        }
        setMessage(action.event.eventType === 'DECLINED'
          ? '응답이 기록되었습니다. 알림 기능은 준비 중입니다.'
          : '관심이 기록되었습니다. 알림 기능은 준비 중이며 실제 알림은 발송되지 않습니다.')
      }
    } catch {
      setFailed(action)
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }, [repository])

  const request = useCallback((selection: Selection, button: HTMLButtonElement) => {
    if (inFlight.current || prompt || failed) return
    trigger.current = button
    const alreadyAsked = completed.current || readStorage('localStorage', promptKey) === '1'
    void send({ ...selection, event: eventFor(selection, 'CLICKED'), next: alreadyAsked ? 'finish' : 'prompt' })
  }, [eventFor, failed, prompt, send])

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

  const context = useMemo(() => ({ blocked: busy || prompt !== null || failed !== null, request, expose }), [busy, expose, failed, prompt, request])
  const error = failed && (
    <div role="alert" className={styles.error}>
      <p>관심을 기록하지 못했습니다.</p>
      <button type="button" disabled={busy} onClick={() => { void send(failed) }}>다시 시도</button>
    </div>
  )

  function answer(eventType: 'CONFIRMED' | 'DECLINED') {
    if (prompt && !failed) void send({ ...prompt, event: eventFor(prompt, eventType), next: 'complete' })
  }

  return (
    <InterestContext.Provider value={context}>
      {children}
      {prompt && (
        <InterestDialog onDismiss={() => answer('DECLINED')}>
          <h2 id="notification-interest-title">알림 신청 의사 확인</h2>
          <p className={styles.target}>{prompt.target.name}</p>
          <p>{`이 ${{ REGION: '지역', COMPLEX: '단지', ANNOUNCEMENT: '공고' }[prompt.target.type]}에 대한 알림을 받으시겠습니까?`}</p>
          <p className={styles.notice}>알림 기능은 준비 중입니다. 신청 의사만 기록하며 실제 알림은 발송되지 않습니다.</p>
          <div className={styles.actions}>
            <button type="button" disabled={busy || failed !== null} onClick={() => answer('CONFIRMED')}>네, 받고 싶어요</button>
            <button type="button" disabled={busy || failed !== null} onClick={() => answer('DECLINED')}>아니요</button>
          </div>
          {busy && <p role="status">관심을 기록하는 중입니다.</p>}
          {error}
        </InterestDialog>
      )}
      {!prompt && (error || message) && (
        <div className={styles.feedback}>{error || <p role="status">{message}</p>}</div>
      )}
    </InterestContext.Provider>
  )
}

function InterestDialog({ children, onDismiss }: { readonly children: ReactNode; readonly onDismiss: () => void }) {
  const panel = useRef<HTMLDivElement>(null)
  useEffect(() => { panel.current?.querySelector<HTMLButtonElement>('button')?.focus() }, [])

  function keyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onDismiss() }
    if (event.key !== 'Tab') return
    const buttons = Array.from(panel.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
    const first = buttons[0]
    const last = buttons.at(-1)
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
      aria-label={`${name} 알림 받기`} disabled={context.blocked}
      onClick={(event) => context.request({ target, source }, event.currentTarget)}>
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" />
      </svg>
      {!iconOnly && '알림 받기'}
    </button>
  )
}
