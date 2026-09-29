import { useCallback, useEffect, useRef, useState } from 'react'
import type { HwpRequest, HwpResponse } from './hwpPreview.worker.ts'
import type { PageSize, TextMatch } from './hwpTextSearch.ts'

type PageImage = { readonly url: string; readonly text: string }
type SearchResult = { readonly pending: boolean; readonly error: boolean; readonly matches: readonly TextMatch[]; readonly current: number }
const emptySearch: SearchResult = { pending: false, error: false, matches: [], current: 0 }

export function useHwpDocument(url: string) {
  const [pages, setPages] = useState<readonly PageSize[]>([])
  const [images, setImages] = useState<ReadonlyMap<number, PageImage>>(new Map())
  const [failed, setFailed] = useState(false)
  const [result, setResult] = useState<SearchResult>(emptySearch)
  const session = useRef<{
    requestPages: (pages: readonly number[]) => void
    search: (query: string) => void
    fail: () => void
  } | null>(null)

  useEffect(() => {
    let alive = true, ready = false, searchId = 0
    let wanted: readonly number[] = [], inflight: number | null = null
    const urls = new Map<number, PageImage>()
    const worker = new Worker(new URL('./hwpPreview.worker.ts', import.meta.url), { type: 'module' })
    let pageTimer: ReturnType<typeof setTimeout> | undefined
    let searchTimer: ReturnType<typeof setTimeout> | undefined
    const dispose = () => {
      alive = false
      clearTimeout(pageTimer); clearTimeout(searchTimer)
      worker.onmessage = null; worker.onerror = null
      worker.terminate()
      for (const image of urls.values()) URL.revokeObjectURL(image.url)
      urls.clear()
      session.current = null
    }
    const fail = () => { dispose(); setImages(new Map()); setFailed(true) }
    const post = (request: HwpRequest) => worker.postMessage(request)
    const renderNext = () => {
      if (!alive || !ready || inflight !== null) return
      const next = wanted.find((page) => !urls.has(page))
      if (next === undefined) return
      inflight = next
      pageTimer = setTimeout(fail, 30_000)
      post({ type: 'page', page: next })
    }
    session.current = {
      fail,
      requestPages(next) {
        wanted = next
        let changed = false
        for (const [page, image] of urls) if (!wanted.includes(page)) {
          URL.revokeObjectURL(image.url); urls.delete(page); changed = true
        }
        if (changed) setImages(new Map(urls))
        renderNext()
      },
      search(query) {
        clearTimeout(searchTimer)
        const id = ++searchId
        setResult({ ...emptySearch, pending: !!query.trim() })
        post({ type: 'search', id, query })
        if (query.trim()) searchTimer = setTimeout(fail, 60_000)
      },
    }
    worker.onerror = fail
    worker.onmessage = ({ data }: MessageEvent<HwpResponse>) => {
      if (!alive) return
      if (data.type === 'error') { fail(); return }
      if (data.type === 'ready') {
        clearTimeout(pageTimer)
        ready = true; setPages(data.pages); renderNext()
      } else if (data.type === 'page') {
        if (data.page !== inflight) return
        clearTimeout(pageTimer); inflight = null
        if (wanted.includes(data.page)) {
          // SVG stays in image context; never insert document markup into the DOM.
          urls.set(data.page, { url: URL.createObjectURL(new Blob([data.svg], { type: 'image/svg+xml' })), text: data.text })
          setImages(new Map(urls))
        }
        renderNext()
      } else if (data.id === searchId) {
        clearTimeout(searchTimer)
        setResult({ ...emptySearch, error: data.type === 'searchError', matches: data.type === 'search' ? data.matches : [] })
      }
    }
    pageTimer = setTimeout(fail, 60_000)
    post({ type: 'open', url })
    return dispose
  }, [url])

  const requestPages = useCallback((pages: readonly number[]) => session.current?.requestPages(pages), [])
  const search = useCallback((query: string) => session.current?.search(query), [])
  const fail = useCallback(() => session.current?.fail(), [])
  const moveMatch = useCallback((backward = false) => setResult((value) => value.matches.length
    ? { ...value, current: (value.current + (backward ? -1 : 1) + value.matches.length) % value.matches.length } : value), [])
  return { pages, images, failed, result, requestPages, search, fail, moveMatch }
}
