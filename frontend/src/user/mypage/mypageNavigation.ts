import { useLocation } from 'react-router'

const pages = new Set(['/mypage/bookmarks', '/mypage/notifications', '/mypage/account', '/mypage/bookmarks?type=ANNOUNCEMENT', '/mypage/notifications?type=ANNOUNCEMENT', '/mypage/notifications?type=REGION'])

export function myPageReturnPath(state: unknown): string | null {
  if (!state || typeof state !== 'object' || !('mypageReturn' in state)) return null
  return typeof state.mypageReturn === 'string' && pages.has(state.mypageReturn) ? state.mypageReturn : null
}

export function useMyPageReturnState() {
  const location = useLocation()
  const type = new URLSearchParams(location.search).get('type')
  const filtered = `${location.pathname}?type=${type}`
  const current = pages.has(filtered) ? filtered : location.pathname
  const path = pages.has(current) ? current : myPageReturnPath(location.state)
  return path ? { mypageReturn: path } : undefined
}
