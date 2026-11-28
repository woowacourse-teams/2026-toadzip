import { Link, useLocation, type LinkProps } from 'react-router'
import type { FeedbackTarget } from './feedbackNavigation'

export function FeedbackLink({ state, feedbackTarget, ...props }: Omit<LinkProps, 'to'> & { readonly feedbackTarget?: FeedbackTarget }) {
  const location = useLocation()
  return <Link {...props} to="/feedback"
    state={{ ...state, feedbackBackground: location, ...(feedbackTarget ? { feedbackTarget } : {}) }} />
}
