import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { getCurrentAdmin } from '../../admin/auth/api'

export function AdminMenuLink() {
  const [isAdmin, setIsAdmin] = useState(false)

  useEffect(() => {
    let pending: AbortController | undefined
    let sessionEnded = false

    function clear() {
      pending?.abort()
      pending = undefined
      setIsAdmin(false)
    }

    async function refresh() {
      if (sessionEnded || document.hidden || pending) return
      const request = new AbortController()
      pending = request
      setIsAdmin(false)
      try {
        const session = await getCurrentAdmin(request.signal)
        if (!request.signal.aborted) setIsAdmin(session.role === 'ADMIN')
      } catch {
        // Only a confirmed administrator sees the link.
      } finally {
        if (pending === request) pending = undefined
      }
    }

    function visibilityChanged() {
      if (document.hidden) clear()
      else void refresh()
    }

    function endSession() {
      sessionEnded = true
      clear()
    }

    void refresh()
    window.addEventListener('focus', refresh)
    window.addEventListener('online', refresh)
    document.addEventListener('visibilitychange', visibilityChanged)
    window.addEventListener('toadzip:session-ended', endSession)
    return () => {
      pending?.abort()
      window.removeEventListener('focus', refresh)
      window.removeEventListener('online', refresh)
      document.removeEventListener('visibilitychange', visibilityChanged)
      window.removeEventListener('toadzip:session-ended', endSession)
    }
  }, [])

  if (!isAdmin) return null
  return <Link className="service-login-link service-admin-link" to="/admin">
    <span className="housing-navigation__multiline-label">관리자<span className="housing-navigation__label-break" /> 메뉴</span>
  </Link>
}
