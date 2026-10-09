import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { requestGuestCancellation, verifyGuestCancellation } from './guestCancellationApi'
import styles from './GuestCancellationPage.module.css'

export function GuestCancellationPage() {
  const inFlight = useRef(false)
  const [cancellationId] = useState(createAnalyticsId)
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [requested, setRequested] = useState(false)
  const [finished, setFinished] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function request(event: FormEvent) {
    event.preventDefault()
    if (inFlight.current) return
    inFlight.current = true
    const submissionId = createAnalyticsId()
    setBusy(true)
    setError('')
    captureProductEvent('guest_cancellation_requested', { cancellation_id: cancellationId, submission_id: submissionId })
    try {
      await requestGuestCancellation(email.trim())
      captureProductEvent('guest_cancellation_request_accepted', { cancellation_id: cancellationId, submission_id: submissionId })
      setRequested(true)
    } catch (cause) {
      captureProductEvent('guest_cancellation_request_failed', { cancellation_id: cancellationId, submission_id: submissionId, failure_reason: 'request_failed' })
      setError(cause instanceof Error ? cause.message : '취소 요청을 접수하지 못했어요.')
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  async function verify(event: FormEvent) {
    event.preventDefault()
    if (inFlight.current) return
    inFlight.current = true
    const submissionId = createAnalyticsId()
    setBusy(true)
    setError('')
    captureProductEvent('guest_cancellation_verification_submitted', { cancellation_id: cancellationId, submission_id: submissionId })
    try {
      await verifyGuestCancellation(email.trim(), code.trim())
      captureProductEvent('guest_bulk_cancellation_completed', { cancellation_id: cancellationId, submission_id: submissionId })
      setFinished(true)
    } catch (cause) {
      captureProductEvent('guest_cancellation_verification_failed', { cancellation_id: cancellationId, submission_id: submissionId, failure_reason: 'request_failed' })
      setError(cause instanceof Error ? cause.message : '취소를 완료하지 못했어요.')
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  return <main className={`${styles.page} ph-no-capture`}>
    <div className={styles.card}>
      <Link className={styles.back} to="/">← 지도로 돌아가기</Link>
      <span className={styles.eyebrow}>이메일 알림</span>
      <h1>비로그인 알림 신청 취소</h1>
      {finished ? <p role="status">해당 이메일로 신청한 비로그인 알림을 모두 취소하고 알림용 이메일을 삭제했어요.</p> : <>
        <p className={styles.intro}>브라우저 데이터를 지워 종 버튼으로 취소할 수 없다면 신청한 이메일 주소로 취소를 요청해 주세요.</p>
        <ol className={styles.steps}>
          <li>아래에서 신청할 때 사용한 이메일 주소를 입력합니다.</li>
          <li>운영팀이 그 주소로 확인 코드를 수동으로 보내드립니다.</li>
          <li>받은 코드를 입력하면 해당 이메일의 비로그인 알림 신청이 모두 취소됩니다.</li>
        </ol>
        <form onSubmit={request}>
          <label htmlFor="cancellation-email">신청한 이메일</label>
          <input id="cancellation-email" type="email" autoComplete="email" maxLength={254} required
            value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy || requested} />
          <button type="submit" disabled={busy || requested}>{requested ? '요청 확인 중' : '취소 요청하기'}</button>
        </form>
        {requested && <>
          <p role="status" className={styles.notice}>해당 주소에 비로그인 신청이 확인되면 운영팀이 코드를 보내드립니다. 코드를 받았다면 아래에 입력해 주세요.</p>
          <button className={styles.secondary} type="button" disabled={busy} onClick={() => { setRequested(false); setCode('') }}>다른 이메일로 요청</button>
          <form onSubmit={verify}>
            <label htmlFor="cancellation-code">이메일로 받은 확인 코드</label>
            <input id="cancellation-code" type="text" autoComplete="one-time-code" maxLength={128} required
              value={code} onChange={(event) => setCode(event.target.value)} disabled={busy} />
            <button type="submit" disabled={busy}>비로그인 알림 모두 취소</button>
          </form>
        </>}
        {error && <p role="alert" className={styles.error}>{error}</p>}
        <p className={styles.footnote}>이 절차는 비로그인 신청에만 적용됩니다. 로그인 후 신청한 알림은 로그인해 종 버튼에서 취소해 주세요.</p>
      </>}
    </div>
  </main>
}
