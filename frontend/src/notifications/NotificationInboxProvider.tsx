import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useNotificationInterests } from '../public-housing/interest/NotificationInterestContext'
import { NotificationInboxContext } from './NotificationInboxContext'
import { notificationRepository, NotificationApiError, type NotificationRepository } from './notificationRepository'
import { useNotificationPolling } from './useNotificationPolling'

export function NotificationInboxProvider({ children, repository = notificationRepository }: { readonly children: ReactNode; readonly repository?: NotificationRepository }) {
  const interests = useNotificationInterests()
  const userId = interests?.mode === 'member' ? interests.userId : null
  const refreshStatus = interests?.resetSession
  const sessionExpired = useCallback(() => { refreshStatus?.() }, [refreshStatus])
  const load = useCallback(async (signal: AbortSignal) => {
    try { return await repository.count(signal) } catch (error) {
      if (!signal.aborted && error instanceof NotificationApiError && error.status === 401) sessionExpired()
      throw error
    }
  }, [repository, sessionExpired])
  const { unreadCount, error, refresh } = useNotificationPolling({ userId, load })
  const [revision, setRevision] = useState(0)
  const channel = useRef<BroadcastChannel | null>(null)
  const invalidate = useCallback((broadcast = true) => {
    refresh(); setRevision(value => value + 1)
    if (broadcast && userId) channel.current?.postMessage({ type: 'invalidate', userId })
  }, [refresh, userId])
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return
    const connection = new BroadcastChannel('toadzip-notifications')
    channel.current = connection
    connection.onmessage = ({ data }: MessageEvent<unknown>) => {
      if (typeof data !== 'object' || !data || !('type' in data)) return
      if (data.type === 'session-ended') { sessionExpired(); return }
      if (data.type === 'invalidate' && 'userId' in data && data.userId === userId) invalidate(false)
    }
    const ended = () => connection.postMessage({ type: 'session-ended' })
    window.addEventListener('toadzip:session-ended', ended)
    return () => { window.removeEventListener('toadzip:session-ended', ended); connection.close(); channel.current = null }
  }, [invalidate, userId, sessionExpired])
  return <NotificationInboxContext.Provider value={{ userId, unreadCount, error, repository, revision, invalidate, sessionExpired }}>{children}</NotificationInboxContext.Provider>
}
