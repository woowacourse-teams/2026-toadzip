import { useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useLocation, useNavigate } from 'react-router'
import { feedbackBackground } from './feedbackNavigation'
import { dismissFeedbackPrompt } from './feedbackPromptSession'
import { isMobileViewport } from '../public-housing/components/useMobileViewport'
import { IconButton } from '../design-system/components/IconButton'
import { lastExplorerPath } from '../user/auth/loginReturn'
import styles from './FeedbackPage.module.css'

// 의견은 구글 폼으로 받는다. 폼 주소의 `viewform` 링크를 넣는다.
export const FEEDBACK_FORM_URL = 'https://docs.google.com/forms/d/e/1FAIpQLSchD6BT5gzS29n6VymijdHGOvnNHj8WDQTIuNjUUpTD_D1Ndw/viewform'

export function FeedbackPage() {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const background = feedbackBackground(location.state)
  function close() {
    if (background) navigate(-1)
    else navigate(lastExplorerPath(), { replace: true })
  }
  useLayoutEffect(() => {
    const previous = document.activeElement
    const dialog = dialogRef.current
    dialog?.showModal()
    headingRef.current?.focus({ preventScroll: true })
    // 폼 안의 제출 여부는 알 수 없으므로, 의견 창을 연 것으로 의견 요청 말풍선을 닫는다.
    dismissFeedbackPrompt()
    return () => {
      dialog?.close()
      const target = previous instanceof HTMLElement && previous !== document.body && previous.isConnected
        ? previous : document.querySelector<HTMLElement>('.service-feedback-link')
      target?.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(<dialog ref={dialogRef} className={`${styles.dialog} ph-no-capture`}
    aria-labelledby="feedback-title" onCancel={event => { event.preventDefault(); close() }}
    onKeyDown={event => event.stopPropagation()}
    onClick={event => {
      if (isMobileViewport() || event.target !== event.currentTarget) return
      const rect = event.currentTarget.getBoundingClientRect()
      if (event.clientX < rect.left || event.clientX > rect.right
        || event.clientY < rect.top || event.clientY > rect.bottom) close()
    }}>
    <header className={styles.heading}>
      <h1 id="feedback-title" ref={headingRef} tabIndex={-1}>의견 보내기</h1>
      <a className={styles.external} href={FEEDBACK_FORM_URL} target="_blank" rel="noreferrer">새 창에서 열기</a>
      <IconButton size="lg" label="의견 보내기 닫기" onClick={close}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg>
      </IconButton>
    </header>
    <iframe className={styles.form} title="의견 설문" src={`${FEEDBACK_FORM_URL}?embedded=true`} />
  </dialog>, document.body)
}
