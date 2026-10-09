import type { DocumentPreviewTelemetry } from './documentAnalytics'
import { useDocumentSearchShortcuts } from './useDocumentSearchShortcuts.ts'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useHwpDocument } from './useHwpDocument.ts'
import DocumentOutline from './DocumentOutline.tsx'
import styles from './HwpDocumentPreview.module.css'
import { useDocumentScrollActivity } from './useDocumentScrollActivity.ts'
import scrollbarStyles from './DocumentScrollbar.module.css'

export default function HwpDocumentPreview(props: { readonly url: string; readonly name: string; readonly telemetry?: DocumentPreviewTelemetry; readonly onRetry?: () => void }) {
  return <HwpPreview key={props.url} {...props} />
}
function HwpPreview(props: { readonly url: string; readonly name: string; readonly telemetry?: DocumentPreviewTelemetry; readonly onRetry?: () => void }) {
  const [attempt, setAttempt] = useState(0)
  return <Document key={attempt} {...props} retry={props.onRetry ?? (() => setAttempt(attempt + 1))} />
}
function Document({ url, name, retry, telemetry }: { readonly url: string; readonly name: string; readonly telemetry?: DocumentPreviewTelemetry; readonly onRetry?: () => void; readonly retry: () => void }) {
  const { pages, outline, images, failed, result, requestPages, search, fail, moveMatch } = useHwpDocument(url)
  const lastNavigation = useRef<typeof result | null>(null)
  const root = useRef<HTMLElement>(null)
  const viewport = useRef<HTMLDivElement>(null)
  useDocumentScrollActivity(viewport)
  const input = useRef<HTMLInputElement>(null)
  const composing = useRef(false)
  const debounce = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const zoomAnchor = useRef<{ page: number; fraction: number } | null>(null)
  const [zoom, setZoom] = useState('fit')
  const [width, setWidth] = useState(800)
  const [page, setPage] = useState(0)
  const [activeOutlineId, setActiveOutlineId] = useState<string | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [debouncing, setDebouncing] = useState(false)
  const ready = pages.length > 0 && !failed
  const layout = useMemo(() => {
    let top = 12
    return pages.map((size) => {
      const scale = zoom === 'fit' ? Math.max(width - 24, 1) / size.width : Number(zoom) / 100
      const frame = { top, width: size.width * scale, height: size.height * scale, scale }
      top += frame.height + 16
      return frame
    })
  }, [pages, width, zoom])
  const last = layout.at(-1)
  const activeMatch = searchOpen && !result.pending ? result.matches[result.current] : undefined
  const outlineAnchors = useMemo(() => outline.flatMap((entry) => {
    const frame = layout[entry.pageNumber - 1]
    return frame ? [{ id: entry.id, top: frame.top + Math.min(entry.y, frame.height / frame.scale) * frame.scale }] : []
  }).sort((a, b) => a.top - b.top), [layout, outline])

  const updateViewport = useCallback(() => {
    const element = viewport.current
    if (!element || !layout.length) return
    const top = element.scrollTop
    const current = Math.max(0, layout.findIndex((frame) => frame.top + frame.height > top + 16))
    let end = current
    while (end + 1 < layout.length && layout[end + 1]!.top < top + element.clientHeight) end++
    setPage(current)
    const marker = top + 32
    let low = 0, high = outlineAnchors.length
    while (low < high) {
      const middle = Math.floor((low + high) / 2)
      if (outlineAnchors[middle]!.top <= marker) low = middle + 1
      else high = middle
    }
    setActiveOutlineId(low ? outlineAnchors[low - 1]!.id : null)
    // Current page first, followed by visible pages and one page of overscan.
    requestPages([current, ...Array.from({ length: end - current }, (_, i) => current + i + 1), end + 1, current - 1]
      .filter((number) => number >= 0 && number < layout.length))
  }, [layout, outlineAnchors, requestPages])

  useEffect(() => {
    const element = viewport.current
    if (!element) return
    const resize = () => setWidth(element.clientWidth)
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(element)
    return () => observer.disconnect()
  }, [failed])
  useLayoutEffect(() => {
    const anchor = zoomAnchor.current
    if (anchor && viewport.current && layout[anchor.page]) {
      const frame = layout[anchor.page]!
      viewport.current.scrollTop = frame.top + anchor.fraction * frame.height
      zoomAnchor.current = null
    }
    updateViewport()
  }, [layout, updateViewport])
  useEffect(() => {
    if (!activeMatch || !viewport.current || lastNavigation.current === result) return
    lastNavigation.current = result
    const frame = layout[activeMatch.page]
    if (!frame) return
    viewport.current.scrollTop = Math.max(frame.top, frame.top + activeMatch.rects[0]!.y * frame.scale - 80)
    viewport.current.scrollLeft = Math.max(0, activeMatch.rects[0]!.x * frame.scale - viewport.current.clientWidth / 2)
    updateViewport()
  }, [activeMatch, result, layout, updateViewport])
  useEffect(() => () => clearTimeout(debounce.current), [])
  useEffect(() => {
    if (searchOpen) { input.current?.focus(); input.current?.select() }
  }, [searchOpen])

  useEffect(() => { if (failed) telemetry?.failed('render_failed') }, [failed, telemetry])

  const openSearch = useCallback(() => {
    telemetry?.action('search_open')
    setSearchOpen(true)
    input.current?.focus(); input.current?.select()
  }, [telemetry])
  const hideSearch = useCallback(() => {
    telemetry?.action('search_close')
    clearTimeout(debounce.current)
    setSearchOpen(false); setQuery(''); setDebouncing(false); search('')
    viewport.current?.focus({ preventScroll: true })
  }, [search, telemetry])
  useDocumentSearchShortcuts({ root: root, ready, searchOpen, openSearch, hideSearch })

  function changeQuery(value: string) {
    setQuery(value)
    clearTimeout(debounce.current)
    // Invalidate outstanding results immediately, before the debounce expires.
    search('')
    setDebouncing(!composing.current && !!value.trim())
    if (!composing.current && value.trim()) debounce.current = setTimeout(() => { setDebouncing(false); telemetry?.action('search'); search(value) }, 200)
  }
  function navigateOutline(id: string) {
    const anchor = outlineAnchors.find((value) => value.id === id)
    if (!anchor || !viewport.current) return
    telemetry?.action('outline')
    viewport.current.scrollTop = Math.max(0, anchor.top - 24)
    viewport.current.scrollLeft = 0
    updateViewport()
    viewport.current.focus({ preventScroll: true })
  }
  if (failed) return <div className={styles.error}>
    <p role="alert">한글 문서를 표시하지 못했습니다. 암호가 있거나 지원하지 않는 문서일 수 있습니다. 다운로드해서 확인해 주세요.</p>
    <button type="button" onClick={retry}>다시 시도</button>
  </div>

  return <section className={`${styles.document} ph-no-capture`} ref={root} aria-label={`${name} 미리보기`}>
    <div className={styles.controls} aria-label="한글 문서 도구">
      <span>{ready ? `${page + 1} / ${pages.length} 페이지` : '한글 문서를 준비하는 중…'}</span>
      <label>크기 <select aria-label="한글 문서 크기" disabled={!ready} value={zoom} onChange={(event) => {
        telemetry?.action('zoom')
        const old = layout[page]
        const fraction = old && viewport.current ? (viewport.current.scrollTop - old.top) / old.height : 0
        zoomAnchor.current = { page, fraction }
        setZoom(event.target.value)
      }}><option value="fit">너비 맞춤</option><option value="100">100%</option><option value="150">150%</option><option value="200">200%</option></select></label>
      <button type="button" aria-label="문서 검색" aria-expanded={searchOpen} disabled={!ready} onClick={openSearch}>검색</button>
    </div>
    {searchOpen && <div className={styles.search} role="search" aria-label="한글 문서 검색">
      <input ref={input} type="search" aria-label="한글 문서 검색어" placeholder="문서에서 검색" value={query}
        onCompositionStart={() => { composing.current = true; setDebouncing(false); clearTimeout(debounce.current); search('') }}
        onCompositionEnd={(event) => { composing.current = false; changeQuery(event.currentTarget.value) }}
        onChange={(event) => changeQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.nativeEvent.isComposing && !composing.current) {
            event.preventDefault()
            if (!result.pending) { telemetry?.action(event.shiftKey ? 'previous_result' : 'next_result'); moveMatch(event.shiftKey) }
          }
        }} />
      <span className={styles.result} role="status">{!query.trim() ? '' : (debouncing || result.pending) ? '검색 중…'
        : result.error ? '문서를 검색하지 못했습니다.' : result.matches.length ? `${result.current + 1} / ${result.matches.length}개` : '검색 결과 없음'}</span>
      <div className={styles.searchActions}>
        <button type="button" aria-label="이전 검색 결과" disabled={result.pending || !result.matches.length} onClick={() => { telemetry?.action('previous_result'); moveMatch(true) }}>↑</button>
        <button type="button" aria-label="다음 검색 결과" disabled={result.pending || !result.matches.length} onClick={() => { telemetry?.action('next_result'); moveMatch() }}>↓</button>
        <button type="button" aria-label="검색 닫기" onClick={hideSearch}>닫기</button>
      </div>
    </div>}
    <div className={styles.viewportShell}>
    <div className={`${styles.viewport} ${scrollbarStyles.scrollbar}`} ref={viewport} role="region" tabIndex={0} aria-label={`${name} 문서`} onScroll={updateViewport}>
      <div className={styles.pages} style={{ height: last ? last.top + last.height + 12 : 0, minWidth: Math.max(0, ...layout.map((frame) => frame.width + 24)) }}>
        {layout.map((frame, number) => <div key={number} className={styles.page} role="region" aria-label={`${number + 1}페이지`}
          style={{ top: frame.top, width: frame.width, height: frame.height }} aria-busy={!images.has(number)}>
          {images.has(number) ? <img src={images.get(number)?.url} alt={`${name} ${number + 1}페이지`} onError={fail} onLoad={(event) => telemetry?.rendered(event.currentTarget)} />
            : <span className={styles.placeholder}>{number + 1}페이지</span>}
          {searchOpen && result.matches.flatMap((match, index) => match.page === number ? match.rects.map((rect, part) => <span
            key={`${index}-${part}`} className={styles.highlight} aria-hidden="true" data-search-active={index === result.current}
            style={{ left: rect.x * frame.scale, top: rect.y * frame.scale, width: rect.width * frame.scale, height: rect.height * frame.scale }} />) : [])}
        </div>)}
      </div>
    </div>
    <DocumentOutline entries={outline} activeId={activeOutlineId} onNavigate={navigateOutline} />
    </div>
    {ready && <details className={styles.text}><summary>현재 페이지 텍스트</summary>
      <pre>{images.get(page)?.text || '추출할 수 있는 텍스트가 없습니다.'}</pre>
    </details>}
  </section>
}
