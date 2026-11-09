export const DISMISSED = 'feedback-prompt-dismissed'
export const STARTED = 'feedback-prompt-started'
export const DISMISS_EVENT = 'feedback-prompt-dismiss'

export function dismissFeedbackPrompt() {
  try { sessionStorage.setItem(DISMISSED, 'true') } catch { /* Storage is optional. */ }
  window.dispatchEvent(new Event(DISMISS_EVENT))
}
