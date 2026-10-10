import { useEffect, useRef, useState } from 'react'
import { issueGuestCancellationCode, loadGuestCancellationRequests, markGuestCancellationCodeSent, reissueGuestCancellationCode, type GuestCancellationRequest } from '../public-housing/interest/guestCancellationApi'
import styles from './GuestCancellationAdminPage.module.css'

export function GuestCancellationAdminPage() {
  const [requests, setRequests] = useState<GuestCancellationRequest[]>([])
  const [codes, setCodes] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [loadError, setLoadError] = useState('')
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [confirmationId, setConfirmationId] = useState<string | null>(null)
  const requestGeneration = useRef(0)
  const mounted = useRef(false)

  async function refresh() {
    if (!mounted.current) return
    const generation = ++requestGeneration.current
    setLoadState('loading')
    setLoadError('')
    try {
      const nextRequests = await loadGuestCancellationRequests()
      if (generation !== requestGeneration.current) return
      setRequests(nextRequests)
      setLoadState('success')
    } catch (cause) {
      if (generation !== requestGeneration.current) return
      setLoadError(cause instanceof Error ? cause.message : '요청 목록을 불러오지 못했습니다.')
      setLoadState('error')
    }
  }

  useEffect(() => {
    mounted.current = true
    void refresh()
    return () => { mounted.current = false; requestGeneration.current += 1 }
  }, [])

  async function issue(id: string, reissue = false) {
    setBusy(true)
    setError('')
    setConfirmationId(null)
    if (reissue) {
      setCodes((current) => {
        const next = { ...current }
        delete next[id]
        return next
      })
    }
    try {
      const issued = await (reissue ? reissueGuestCancellationCode(id) : issueGuestCancellationCode(id))
      setCodes((current) => ({ ...current, [id]: issued.code }))
      await refresh()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '코드를 만들지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  async function markSent(id: string) {
    setBusy(true)
    setError('')
    try {
      await markGuestCancellationCodeSent(id, codes[id])
      setCodes((current) => {
        const next = { ...current }
        delete next[id]
        return next
      })
      await refresh()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '발송 완료를 기록하지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return <section className={styles.page}>
    <div className={styles.heading}>
      <div>
        <h1>비로그인 알림 취소 요청</h1>
        <p>코드를 신청 주소로 직접 보낸 뒤 발송 완료를 기록하세요. 사용자가 코드를 입력하면 취소됩니다.</p>
        <p>발신: toadzip.official@gmail.com · 확인: 평일 10:00 / 17:00 (한국 시간)</p>
      </div>
      <button type="button" disabled={busy} onClick={() => { setError('');void refresh() }}>목록 새로고침</button>
    </div>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {loadError && <p role="alert" className={styles.error}>{loadError}</p>}
    {loadState === 'loading' && <p role="status">목록을 불러오는 중…</p>}
    {loadState === 'success' && requests.length === 0 && <p>대기 중인 요청이 없습니다.</p>}
    <ul className={styles.list}>
      {requests.map((request) => <li key={request.id}>
        <div>
          <strong>{request.email}</strong>
          <span>요청 {new Date(request.requestedAt).toLocaleString('ko-KR')}</span>
          {request.codeExpiresAt && <span>코드 만료 {new Date(request.codeExpiresAt).toLocaleString('ko-KR')}</span>}
          {request.codeSentAt && <span>이메일 발송 완료 {new Date(request.codeSentAt).toLocaleString('ko-KR')} · {request.codeSentBy}</span>}
        </div>
        {codes[request.id] ? <div className={styles.code}>
          <label htmlFor={`code-${request.id}`}>한 번만 표시되는 코드 · 신청 주소로 수동 발송</label>
          <input id={`code-${request.id}`} readOnly value={codes[request.id]} onFocus={(event) => event.target.select()} />
          <button type="button" disabled={busy} onClick={() => { void markSent(request.id) }}>이메일 발송 완료 기록</button>
        </div> : request.codeExpiresAt && new Date(request.codeExpiresAt) > new Date()
          ? <span>{request.codeSentAt ? '코드 발송 완료 · 사용자 확인 대기' : '발급된 코드를 잃어버렸다면 다시 만들 수 있습니다.'}</span>
          : !request.codeExpiresAt && <button type="button" disabled={busy} onClick={() => { void issue(request.id) }}>확인 코드 만들기</button>}
        {request.codeExpiresAt && (confirmationId === request.id
          ? <div className={styles.confirmation}>
            <p>새 코드를 만들면 기존 코드는 사용할 수 없습니다. 다시 만들어 발송할까요?</p>
            <button type="button" disabled={busy} onClick={() => { void issue(request.id, true) }}>기존 코드 무효화하고 다시 만들기</button>
            <button type="button" disabled={busy} onClick={() => setConfirmationId(null)}>돌아가기</button>
          </div>
          : <button type="button" disabled={busy} onClick={() => setConfirmationId(request.id)}>코드 다시 만들기</button>)}
      </li>)}
    </ul>
  </section>
}
