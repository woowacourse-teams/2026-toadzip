import { useEffect, useState } from 'react'
import { issueGuestCancellationCode, loadGuestCancellationRequests, type GuestCancellationRequest } from '../public-housing/interest/guestCancellationApi'
import styles from './GuestCancellationAdminPage.module.css'

export function GuestCancellationAdminPage() {
  const [requests, setRequests] = useState<GuestCancellationRequest[]>([])
  const [codes, setCodes] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function refresh() {
    setError('')
    try {
      setRequests(await loadGuestCancellationRequests())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '요청 목록을 불러오지 못했습니다.')
    }
  }

  useEffect(() => { void refresh() }, [])

  async function issue(id: string) {
    setBusy(true)
    setError('')
    try {
      const issued = await issueGuestCancellationCode(id)
      setCodes((current) => ({ ...current, [id]: issued.code }))
      await refresh()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '코드를 만들지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return <section className={styles.page}>
    <div className={styles.heading}>
      <div>
        <h1>비로그인 알림 취소 요청</h1>
        <p>신청 주소로 확인 코드를 수동 발송한 뒤 사용자가 입력하면 취소가 완료됩니다.</p>
      </div>
      <button type="button" onClick={() => { void refresh() }}>목록 새로고침</button>
    </div>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {requests.length === 0 && <p>대기 중인 요청이 없습니다.</p>}
    <ul className={styles.list}>
      {requests.map((request) => <li key={request.id}>
        <div>
          <strong>{request.email}</strong>
          <span>요청 {new Date(request.requestedAt).toLocaleString('ko-KR')}</span>
          {request.codeExpiresAt && <span>코드 만료 {new Date(request.codeExpiresAt).toLocaleString('ko-KR')}</span>}
        </div>
        {codes[request.id] ? <div className={styles.code}>
          <label htmlFor={`code-${request.id}`}>한 번만 표시되는 코드 · 신청 주소로 수동 발송</label>
          <input id={`code-${request.id}`} readOnly value={codes[request.id]} onFocus={(event) => event.target.select()} />
        </div> : <button type="button" disabled={busy || Boolean(request.codeExpiresAt && new Date(request.codeExpiresAt) > new Date())}
          onClick={() => { void issue(request.id) }}>확인 코드 만들기</button>}
      </li>)}
    </ul>
  </section>
}
