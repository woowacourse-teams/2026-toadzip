import { useCallback, useEffect, useRef, useState } from 'react'
export function useNotificationPolling({ userId, load }: {
  readonly userId: string | null; readonly load: (signal: AbortSignal) => Promise<number>
}) {
  const [state, setState] = useState<{ userId: string | null; count: number | null; error: string | null }>({ userId: null, count: null, error: null })
  const request = useRef<() => void>(() => {})
  const refresh = useCallback(() => request.current(), [])
  useEffect(() => {
    let disposed = false
    let running = false
    let dirty = false
    let controller: AbortController | null = null
    const active = () => !!userId && document.visibilityState === 'visible' && navigator.onLine
    async function run() {
      if (disposed || !active()) return
      if (running) { dirty = true; return }
      running = true
      controller = new AbortController()
      try {
        const count = await load(controller.signal)
        if (!disposed) setState({ userId, count, error: null })
      } catch {
        if (!disposed) setState(current => ({ userId, count: current.userId === userId ? current.count : null, error: '알림 개수를 불러오지 못했어요.' }))
      } finally {
        running = false
        if (!disposed && dirty) { dirty = false; void run() }
      }
    }
    const trigger = () => { void run() }
    request.current = trigger
    trigger()
    const timer = window.setInterval(trigger, 60_000)
    document.addEventListener('visibilitychange', trigger)
    window.addEventListener('online', trigger)
    return () => {
      disposed = true; controller?.abort(); request.current = () => {}
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', trigger)
      window.removeEventListener('online', trigger)
    }
  }, [userId, load])
  return { unreadCount: state.userId === userId && userId ? state.count : null, error: state.userId === userId && userId ? state.error : null, refresh }
}
