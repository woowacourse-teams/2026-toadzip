import { Link, useLocation, type LinkProps } from 'react-router'

export function FeedbackLink({ state, ...props }: Omit<LinkProps, 'to'>) {
  const location = useLocation()
  return <Link {...props} to="/feedback" state={{ ...state, feedbackBackground: location }} />
}
