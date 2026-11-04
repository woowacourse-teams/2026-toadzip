import { useEffect, useState } from 'react'
import { FeedbackLink } from './FeedbackLink'
import styles from './FeedbackPrompt.module.css'

import { DISMISSED, STARTED, DISMISS_EVENT, dismissFeedbackPrompt } from './feedbackPromptSession'

export function FeedbackPrompt() {
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    let dismissed = false
    let start = Date.now()
    if (!Number.isFinite(start)) return
    try {
      if (sessionStorage.getItem(DISMISSED)) return
      const saved = sessionStorage.getItem(STARTED)
      if (saved && Number.isFinite(Number(saved))) start = Number(saved)
      else sessionStorage.setItem(STARTED, String(start))
    } catch { /* Continue without persistent session state. */ }
    const show = () => {
      if (!dismissed && Date.now() - start >= 60_000 && document.visibilityState === 'visible') setVisible(true)
    }
    const dismiss = () => { dismissed = true; setVisible(false) }
    const timer = window.setTimeout(show, Math.max(0, 60_000 - (Date.now() - start)))
    document.addEventListener('visibilitychange', show)
    window.addEventListener(DISMISS_EVENT, dismiss)
    return () => {
      window.clearTimeout(timer)
      document.removeEventListener('visibilitychange', show)
      window.removeEventListener(DISMISS_EVENT, dismiss)
    }
  }, [])
  return visible ? <aside className={styles.prompt} aria-label="의견 요청">
    <button type="button" className={styles.close} aria-label="의견 요청 닫기" onClick={dismissFeedbackPrompt}>×</button>
    <p>복덕방, 써 보니 어떠셨나요?</p>
    <FeedbackLink onClick={dismissFeedbackPrompt}>의견을 들려주세요 <span aria-hidden="true">→</span></FeedbackLink>
  </aside> : null
}
