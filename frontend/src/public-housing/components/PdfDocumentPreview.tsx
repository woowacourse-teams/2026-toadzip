import type { DocumentPreviewTelemetry } from './documentAnalytics'
import { useDocumentSearchShortcuts } from './useDocumentSearchShortcuts.ts'
import { useCallback, useEffect, useRef, useState } from 'react'
import pdfViewerStyles from 'pdfjs-dist/legacy/web/pdf_viewer.css?inline'
import DocumentOutline from './DocumentOutline.tsx'
import { usePdfViewer } from './usePdfViewer.ts'
import styles from './PdfDocumentPreview.module.css'
import { useDocumentScrollActivity } from './useDocumentScrollActivity.ts'
import scrollbarStyles from './DocumentScrollbar.module.css'

// Keep the SDK's :root variables and generic annotation styles inside this viewer.
// pdf.js는 쪽 테두리(9px)를 content-box로 계산한다. 앱 전역의 `* { box-sizing: border-box }`가 덮으면 그림만 테두리만큼 줄어
// 글자층·검색 강조가 아래·오른쪽으로 밀리므로 쪽 상자는 content-box로 되돌린다.
const scopedViewerStyles = `@scope (.${styles.document}) { ${pdfViewerStyles.replaceAll(':root', ':scope')} .page { box-sizing: content-box; } }`

/**
 * 외부에서 근거 위치를 지정한다. nonce가 바뀔 때마다 해당 쪽으로 이동하고 query를 강조한다.
 * context가 있으면 같은 텍스트 줄에 그 표기가 있는 값만 강조하고, 찾지 못하면 아무것도 강조하지 않고 quote와 함께 알린다.
 */
export type PdfFocus = { readonly page: number; readonly query: string | readonly string[]; readonly nonce: number; readonly context?: readonly string[]; readonly quote?: string }
type PdfPreviewProps = { readonly url: string; readonly name: string; readonly telemetry?: DocumentPreviewTelemetry; readonly focus?: PdfFocus | null }

export default function PdfDocumentPreview(props: PdfPreviewProps) {
  return <PdfPreview key={props.url} {...props} />
}

function PdfPreview({ url, name, telemetry, focus }: PdfPreviewProps) {
  const rootRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<HTMLDivElement>(null)
  useDocumentScrollActivity(containerRef)
  const inputRef = useRef<HTMLInputElement>(null)
  const composing = useRef(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [scale, setScale] = useState('page-width')
  const { page, pages, ready, error, result, search, closeSearch, setScale: applyScale, goToPage, outline, activeOutlineId, navigateOutline } = usePdfViewer(url, containerRef, viewerRef, telemetry)

  useEffect(() => {
    if (!ready || !focus) return
    // Keep keyboard focus in the review and highlight only on the evidence page.
    goToPage(focus.page)
    search(focus.query, false, false, focus.page, focus.context ?? null)
  }, [ready, focus, goToPage, search])

  const openSearch = useCallback(() => {
    telemetry?.action('search_open')
    setSearchOpen(true)
    inputRef.current?.focus()
    inputRef.current?.select()
    if (query) search(query)
  }, [query, search, telemetry])
  const hideSearch = useCallback(() => {
    telemetry?.action('search_close')
    setSearchOpen(false)
    closeSearch()
    containerRef.current?.focus({ preventScroll: true })
  }, [closeSearch, telemetry])

  useEffect(() => {
    if (searchOpen) {
      inputRef.current?.focus()
      inputRef.current?.select()
    }
  }, [searchOpen])

  useEffect(() => {
    if (!query.trim() || composing.current) return
    const timer = setTimeout(() => telemetry?.action('search'), 250)
    return () => clearTimeout(timer)
  }, [query, telemetry])

  useDocumentSearchShortcuts({ root: rootRef, ready, searchOpen, openSearch, hideSearch })

  const resultText = !query ? '' : result.pending ? '검색 중…' : result.notFound ? '검색 결과 없음' : `${result.current} / ${result.total}개`
  return <div className={`${styles.document} ph-no-capture`} ref={rootRef}>
    <style>{scopedViewerStyles}</style>
    <div className={styles.controls} aria-label="PDF 도구">
      <span>{pages > 0 ? `${page} / ${pages} 페이지` : 'PDF를 준비하는 중…'}</span>
      <label>크기 <select aria-label="PDF 크기" disabled={!ready} value={scale} onChange={(event) => {
        telemetry?.action('zoom')
        setScale(event.target.value)
        applyScale(event.target.value)
      }}><option value="page-width">너비 맞춤</option><option value="1.5">150%</option><option value="2">200%</option></select></label>
      <button type="button" aria-label="문서 검색" aria-expanded={searchOpen} disabled={!ready}
        onClick={openSearch}>검색</button>
    </div>
    {searchOpen && <div className={styles.search} role="search" aria-label="PDF 문서 검색">
      <input ref={inputRef} type="search" aria-label="PDF 검색어" placeholder="문서에서 검색" value={query}
        onCompositionStart={() => { composing.current = true }}
        onCompositionEnd={(event) => {
          composing.current = false
          setQuery(event.currentTarget.value)
          search(event.currentTarget.value)
        }}
        onChange={(event) => {
          setQuery(event.target.value)
          if (!composing.current) search(event.target.value)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.nativeEvent.isComposing && !composing.current) {
            event.preventDefault()
            telemetry?.action(event.shiftKey ? 'previous_result' : 'next_result')
            search(query, true, event.shiftKey)
          }
        }} />
      <span className={styles.result} role="status">{resultText}</span>
      <div className={styles.searchActions}>
        <button type="button" aria-label="이전 검색 결과" disabled={!query || result.total === 0}
          onClick={() => { telemetry?.action('previous_result'); search(query, true, true) }}>↑</button>
        <button type="button" aria-label="다음 검색 결과" disabled={!query || result.total === 0}
          onClick={() => { telemetry?.action('next_result'); search(query, true) }}>↓</button>
        <button type="button" aria-label="검색 닫기" onClick={hideSearch}>닫기</button>
      </div>
    </div>}
    {error && <p role="alert" className={styles.error}>PDF를 표시하지 못했어요. 다운로드해서 확인해 주세요.</p>}
    <div className={styles.viewport}>
      <div ref={containerRef} className={`${styles.scroll} ${scrollbarStyles.scrollbar}`} role="region" aria-label={`${name} 문서`} tabIndex={0}>
        <div ref={viewerRef} className="pdfViewer" />
      </div>
      {ready && !error && <DocumentOutline entries={outline} activeId={activeOutlineId} onNavigate={(id) => { telemetry?.action('outline'); navigateOutline(id) }} />}
    </div>
  </div>
}
