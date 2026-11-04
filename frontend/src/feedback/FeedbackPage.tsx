import { captureProductEvent, createAnalyticsId } from '../analytics/productAnalytics'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLocation, useNavigate } from 'react-router'
import { feedbackBackground } from './feedbackNavigation'
import { dismissFeedbackPrompt } from './feedbackPromptSession'
import { surveyAnswers, type SurveyAnswer } from './survey'
import { isMobileViewport } from '../public-housing/components/useMobileViewport'
import { IconButton } from '../design-system/components/IconButton'
import { lastExplorerPath } from '../user/auth/loginReturn'
import { Button } from '../design-system/components/Button'
import { submitFeedback } from './api'
import styles from './FeedbackPage.module.css'

const MAX_LENGTH = 2000

export function FeedbackPage() {
  const [content, setContent] = useState('')
  const [surveyAnswer, setSurveyAnswer] = useState<SurveyAnswer | null>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const firstAnswer = useRef<HTMLInputElement>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const background = feedbackBackground(location.state)
  function close() {
    if (inFlight.current) return
    if (background) navigate(-1)
    else navigate(lastExplorerPath(), { replace: true })
  }
  useLayoutEffect(() => {
    const previous = document.activeElement
    const dialog = dialogRef.current
    dialog?.showModal()
    headingRef.current?.focus({ preventScroll: true })
    return () => {
      dialog?.close()
      const target = previous instanceof HTMLElement && previous !== document.body && previous.isConnected
        ? previous : document.querySelector<HTMLElement>('.service-feedback-link')
      target?.focus({ preventScroll: true })
    }
  }, [])
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
    if (!surveyAnswer) {
      setError('설문 답변을 선택해 주세요.')
      firstAnswer.current?.focus()
      return
    }
    if (content.length > MAX_LENGTH) {
      setError('의견을 2,000자 이하로 입력해 주세요.')
      textarea.current?.focus()
      return
    }
    const submissionId = createAnalyticsId()
    captureProductEvent('feedback_submitted', { feedback_id: feedbackId, submission_id: submissionId })
    inFlight.current = true
    setPending(true)
    setError('')
    try {
      await submitFeedback(content.trim(), surveyAnswer)
      dismissFeedbackPrompt()
      captureProductEvent('feedback_succeeded', { feedback_id: feedbackId, submission_id: submissionId })
      setContent('')
      setSubmitted(true)
    } catch {
      captureProductEvent('feedback_failed', { feedback_id: feedbackId, submission_id: submissionId, failure_reason: 'request_failed' })
      setError('의견을 보내지 못했어요. 작성한 내용을 확인하고 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
      setPending(false)
    }
  }

  return createPortal(<dialog ref={dialogRef} className={`${styles.dialog} ph-no-capture`}
    aria-labelledby="feedback-title" onCancel={event => { event.preventDefault(); close() }}
    onKeyDown={event => event.stopPropagation()}
    onClick={event => {
      if (isMobileViewport() || event.target !== event.currentTarget) return
      const rect = event.currentTarget.getBoundingClientRect()
      if (event.clientX < rect.left || event.clientX > rect.right
        || event.clientY < rect.top || event.clientY > rect.bottom) close()
    }}>
    <IconButton size="lg" className={styles.close} label="의견 보내기 닫기" disabled={pending} onClick={close}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg>
    </IconButton>
    <div className={styles.main}>
      <div className={styles.heading}>
        <span className={styles.headingIcon} aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
          <path d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 3V6a2 2 0 0 1 2-2Z" /><path d="M8 9h8M8 13h5" />
        </svg></span>
        <h1 id="feedback-title" ref={headingRef} tabIndex={-1}>의견 보내기</h1>
        <p className={styles.introduction}>불편했던 점이나 개선 아이디어를 자유롭게 남겨 주세요.</p>
      </div>
      {submitted ? <section className={styles.confirmation} role="status">
        <span className={styles.successIcon} aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 12 4 4 8-8" /></svg></span>
        <h2 ref={confirmation} tabIndex={-1}>의견이 접수되었어요.</h2>
        <p>남겨 주신 의견은 서비스 개선에 참고할게요.</p>
        <Button variant="secondary" onClick={close}>닫기</Button>
      </section> : <form className={styles.form} noValidate aria-label="의견 접수" aria-busy={pending}
        onSubmit={event => { event.preventDefault(); void submit() }}>
        <fieldset className={styles.survey} disabled={pending} aria-describedby={error ? 'feedback-error' : undefined}>
          <legend>이 제품을 더 이상 쓸 수 없다면 어떻겠습니까? <span className={styles.required}>필수</span></legend>
          <div className={styles.answers}>{Object.entries(surveyAnswers).map(([value, label], index) => <label key={value} className={styles.answer}>
            <input ref={index === 0 ? firstAnswer : undefined} type="radio" name="surveyAnswer" value={value} required
              checked={surveyAnswer === value} onChange={() => {
                if (!started.current) { started.current = true; captureProductEvent('feedback_started', { feedback_id: feedbackId }) }
                setSurveyAnswer(value as SurveyAnswer); setError('')
              }} />
            <span>{label}</span>
          </label>)}</div>
        </fieldset>
        <div className={styles.comment}>
        <div className={styles.labelRow}><label htmlFor="feedback-content">의견 내용 <span className={styles.optional}>선택</span></label><span id="feedback-count" className={styles.count}><strong>{content.length.toLocaleString('ko-KR')}</strong> / 2,000자</span></div>
        <textarea ref={textarea} id="feedback-content" name="content" rows={5} maxLength={MAX_LENGTH}
          value={content} disabled={pending} aria-invalid={Boolean(error) && content.length > MAX_LENGTH}
          aria-describedby={`feedback-hint feedback-count${error ? ' feedback-error' : ''}`}
          placeholder="어떤 상황에서 불편했는지, 어떻게 개선되면 좋을지 알려 주세요."
          onChange={event => {
            if (!started.current) {
              started.current = true
              captureProductEvent('feedback_started', { feedback_id: feedbackId })
            }
            setContent(event.target.value); setError('')
          }} />
        </div>
        {error && <p id="feedback-error" className={styles.error} role="alert">{error}</p>}
        <div className={styles.formFooter}>
          <p className={styles.hint} id="feedback-hint">로그인 없이 보낼 수 있어요. 이름, 전화번호 등 개인정보는 적지 말아 주세요. 이 창구는 의견 수집용이며 개별 답변은 제공하지 않아요.</p>
          <Button className={styles.submitButton} type="submit" size="lg" disabled={pending}>{pending ? '보내는 중…' : '의견 보내기'}</Button>
        </div>
      </form>}
    </div>
  </dialog>, document.body)
}
