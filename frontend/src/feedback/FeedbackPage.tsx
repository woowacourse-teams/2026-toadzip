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
    inFlight.current = true
    setPending(true)
    setError('')
    try {
      await submitFeedback(content.trim())
      setContent('')
      setSubmitted(true)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '의견을 보내지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
      setPending(false)
    }
  }

  return <div className={styles.page}>
    <header className={styles.header}><BrandLink /><Link to="/">지도로 돌아가기</Link></header>
    <main className={styles.main}>
      <h1>의견 보내기</h1>
      <p className={styles.introduction}>이용 중 불편했던 점이나 더 나아졌으면 하는 점을 자유롭게 남겨 주세요.</p>
      {submitted ? <section className={styles.confirmation} role="status">
        <h2 ref={confirmation} tabIndex={-1}>의견이 접수되었습니다.</h2>
        <p>남겨 주신 의견은 서비스 개선에 참고하겠습니다.</p>
        <Link to="/">지도로 돌아가기</Link>
      </section> : <form className={styles.form} noValidate aria-label="의견 접수" aria-busy={pending}
        onSubmit={event => { event.preventDefault(); void submit() }}>
        <label htmlFor="feedback-content">의견 내용</label>
        <p className={styles.hint} id="feedback-hint">로그인 없이 보낼 수 있어요. 이름, 전화번호 등 개인정보는 적지 말아 주세요.</p>
        <textarea ref={textarea} id="feedback-content" name="content" rows={9} required maxLength={MAX_LENGTH}
          value={content} disabled={pending} aria-invalid={Boolean(error)}
          aria-describedby={`feedback-hint feedback-count${error ? ' feedback-error' : ''}`}
          placeholder="어떤 상황에서 불편했는지, 어떻게 개선되면 좋을지 알려 주세요."
          onChange={event => { setContent(event.target.value); setError('') }} />
        <span id="feedback-count" className={styles.count}>{content.length.toLocaleString('ko-KR')} / 2,000자</span>
        {error && <p id="feedback-error" className={styles.error} role="alert">{error}</p>}
        <Button type="submit" size="lg" disabled={pending}>{pending ? '보내는 중…' : '의견 보내기'}</Button>
        <p className={styles.hint}>이 창구는 의견 수집용이며 개별 답변은 제공하지 않습니다.</p>
      </form>}
    </main>
  </div>
}
