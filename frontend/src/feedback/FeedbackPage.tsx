import { captureProductEvent, createAnalyticsId } from '../analytics/productAnalytics'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { BrandLink } from '../BrandLink'
import { Button } from '../design-system/components/Button'
import { submitFeedback } from './api'
import styles from './FeedbackPage.module.css'

const MAX_LENGTH = 2000

export function FeedbackPage() {
  const [content, setContent] = useState('')
  const [pending, setPending] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState('')
  const [feedbackId] = useState(createAnalyticsId)
  const started = useRef(false)
  const inFlight = useRef(false)
  const textarea = useRef<HTMLTextAreaElement>(null)
  const confirmation = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (submitted) confirmation.current?.focus()
  }, [submitted])

  async function submit() {
    if (inFlight.current) return
    if (!content.trim() || content.length > MAX_LENGTH) {
      setError('의견을 1자 이상 2,000자 이하로 입력해 주세요.')
      textarea.current?.focus()
      return
    }
    const submissionId = createAnalyticsId()
    captureProductEvent('feedback_submitted', { feedback_id: feedbackId, submission_id: submissionId })
    inFlight.current = true
    setPending(true)
    setError('')
    try {
      await submitFeedback(content.trim())
      captureProductEvent('feedback_succeeded', { feedback_id: feedbackId, submission_id: submissionId })
      setContent('')
      setSubmitted(true)
    } catch (cause) {
      captureProductEvent('feedback_failed', { feedback_id: feedbackId, submission_id: submissionId, failure_reason: 'request_failed' })
      setError(cause instanceof Error ? cause.message : '의견을 보내지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
      setPending(false)
    }
  }

  return <div className={`${styles.page} ph-no-capture`}>
    <header className={styles.header}><div className={styles.headerInner}>
      <BrandLink /><Link className={styles.backLink} to="/">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
        지도로 돌아가기
      </Link>
    </div></header>
    <main className={styles.main}>
      <div className={styles.heading}>
        <span className={styles.headingIcon} aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
          <path d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 3V6a2 2 0 0 1 2-2Z" /><path d="M8 9h8M8 13h5" />
        </svg></span>
        <h1>의견 보내기</h1>
        <p className={styles.introduction}>불편했던 점이나 개선 아이디어를 자유롭게 남겨 주세요.</p>
      </div>
      {submitted ? <section className={styles.confirmation} role="status">
        <span className={styles.successIcon} aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 12 4 4 8-8" /></svg></span>
        <h2 ref={confirmation} tabIndex={-1}>의견이 접수되었습니다.</h2>
        <p>남겨 주신 의견은 서비스 개선에 참고하겠습니다.</p>
        <Link to="/">지도로 돌아가기</Link>
      </section> : <form className={styles.form} noValidate aria-label="의견 접수" aria-busy={pending}
        onSubmit={event => { event.preventDefault(); void submit() }}>
        <div className={styles.labelRow}><label htmlFor="feedback-content">의견 내용</label></div>
        <textarea ref={textarea} id="feedback-content" name="content" rows={7} required maxLength={MAX_LENGTH}
          value={content} disabled={pending} aria-invalid={Boolean(error)}
          aria-describedby={`feedback-hint feedback-count${error ? ' feedback-error' : ''}`}
          placeholder="어떤 상황에서 불편했는지, 어떻게 개선되면 좋을지 알려 주세요."
          onChange={event => {
            if (!started.current) {
              started.current = true
              captureProductEvent('feedback_started', { feedback_id: feedbackId })
            }
            setContent(event.target.value); setError('')
          }} />
        <div className={styles.inputMeta}>
          <p className={styles.hint} id="feedback-hint">로그인 없이 보낼 수 있어요. 이름, 전화번호 등 개인정보는 적지 말아 주세요.</p>
          <span id="feedback-count" className={styles.count}><strong>{content.length.toLocaleString('ko-KR')}</strong> / 2,000자</span>
        </div>
        {error && <p id="feedback-error" className={styles.error} role="alert">{error}</p>}
        <div className={styles.formFooter}>
          <p className={styles.footnote}>이 창구는 의견 수집용이며 개별 답변은 제공하지 않습니다.</p>
          <Button className={styles.submitButton} type="submit" size="lg" disabled={pending}>{pending ? '보내는 중…' : '의견 보내기'}</Button>
        </div>
      </form>}
    </main>
  </div>
}
