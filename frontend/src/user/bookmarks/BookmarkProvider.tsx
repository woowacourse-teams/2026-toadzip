import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { BookmarkContext, bookmarkKey, type BookmarkContextValue } from './BookmarkContext'
import { getCurrentUser } from '../auth/api'
import { bookmarkRepository, BookmarkSessionExpired, type BookmarkItem, type BookmarkRepository, type BookmarkType } from './bookmarkRepository'
export function BookmarkProvider({ children, repository = bookmarkRepository, loadUser = getCurrentUser }: {
  readonly children: ReactNode; readonly repository?: BookmarkRepository; readonly loadUser?: typeof getCurrentUser
}) {
  const [mode, setMode] = useState<BookmarkContextValue['mode']>('loading')
  const [listStatus, setListStatus] = useState<BookmarkContextValue['listStatus']>('loading')
  const accountId = useRef<number | null>(null)
  const [user, setUser] = useState<BookmarkContextValue['user']>(null)
  const [items, setItems] = useState<readonly BookmarkItem[]>([])
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set())
  const [error, setError] = useState('')
  const [optimistic, setOptimistic] = useState<ReadonlyMap<string, boolean>>(new Map())
  const generation = useRef(0)
  const snapshotVersion = useRef(0)
  const inFlight = useRef(new Set<string>())
  const controller = useRef<AbortController | null>(null)
  const reset = useCallback(() => {
    generation.current++; controller.current?.abort(); inFlight.current.clear()
    accountId.current = null
    setUser(null); setItems([]); setPending(new Set()); setOptimistic(new Map()); setError(''); setMode('guest')
  }, [])
  const reload = useCallback(() => {
    const version = ++generation.current
    controller.current?.abort()
    const request = new AbortController(); controller.current = request
    accountId.current = null
    inFlight.current.clear(); setPending(new Set()); setOptimistic(new Map()); setItems([]); setUser(null); setMode('loading'); setListStatus('loading'); setError('')
    void loadUser().then(async (account) => {
      if (version !== generation.current) return
      if (!account) { reset(); return }
      accountId.current = account.id
      setUser(account); setMode('member')
      const saved = await repository.list(request.signal)
      if (version !== generation.current) return
      setItems(saved); setListStatus('ready')
    }).catch((failure: unknown) => {
      if (version !== generation.current) return
      if (failure instanceof BookmarkSessionExpired) { reset(); return }
      setError('북마크를 불러오지 못했습니다. 다시 시도해 주세요.'); setListStatus('error')
      if (accountId.current === null) setMode('error')
    })
  }, [loadUser, repository, reset])
  useEffect(() => {
    const currentGeneration = generation
    const currentController = controller
    const currentAccountId = accountId
    reload()
    window.addEventListener('toadzip:logout', reset)
    window.addEventListener('toadzip:session-ended', reset)
    window.addEventListener('focus', reload)
    return () => { currentGeneration.current++; currentAccountId.current = null; currentController.current?.abort(); window.removeEventListener('toadzip:logout', reset); window.removeEventListener('toadzip:session-ended', reset); window.removeEventListener('focus', reload) }
  }, [reload, reset])
  async function toggle(type: BookmarkType, id: string) {
    const key = bookmarkKey(type, id)
    if (mode !== 'member' || listStatus !== 'ready' || inFlight.current.has(key)) return
    const version = generation.current
    const mutationAccountId = accountId.current
    const saved = items.some((item) => item.type === type && item.targetId === id)
    inFlight.current.add(key); setPending(new Set(inFlight.current)); setError('')
    snapshotVersion.current++
    setOptimistic(current => new Map(current).set(key, !saved))
    try {
      if (saved) await repository.remove(type, id)
      else await repository.save(type, id)
      if (version !== generation.current) return
      // Read the server after writes: its timestamps and availability are authoritative.
      const snapshotOrder = ++snapshotVersion.current
      const snapshot = await repository.list(controller.current?.signal)
      if (version === generation.current) {
        setItems(current => {
          if (snapshotOrder === snapshotVersion.current) return snapshot
          // A newer write can invalidate this full snapshot, but this target's
          // response still completes its own operation without undoing siblings.
          const ownItem = snapshot.find(item => bookmarkKey(item.type, item.targetId) === key)
          const remaining = current.filter(item => bookmarkKey(item.type, item.targetId) !== key)
          return ownItem ? [ownItem, ...remaining] : remaining
        })
      }
    } catch (failure) {
      if (version !== generation.current) return
      if (failure instanceof BookmarkSessionExpired) { reset(); return }
      setError('북마크를 변경하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      if (version === generation.current) {
        inFlight.current.delete(key); setPending(new Set(inFlight.current))
        setOptimistic(current => { const next = new Map(current); next.delete(key); return next })
      }
      else if (mutationAccountId !== null && accountId.current === mutationAccountId) reload()
    }
  }
  return <BookmarkContext.Provider value={{ mode, listStatus, user, items: items.filter(item => optimistic.get(bookmarkKey(item.type, item.targetId)) !== false), optimistic, pending, error, reload, toggle }}>{children}</BookmarkContext.Provider>
}
