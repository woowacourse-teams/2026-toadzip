import { useDocumentSearchShortcuts } from './useDocumentSearchShortcuts.ts'
import { useCallback, useEffect, useRef, useState } from 'react'
import pdfViewerStyles from 'pdfjs-dist/legacy/web/pdf_viewer.css?inline'
import DocumentOutline from './DocumentOutline.tsx'
import { usePdfViewer } from './usePdfViewer.ts'
import styles from './PdfDocumentPreview.module.css'
import { useDocumentScrollActivity } from './useDocumentScrollActivity.ts'
import scrollbarStyles from './DocumentScrollbar.module.css'

// Keep the SDK's :root variables and generic annotation styles inside this viewer.
const scopedViewerStyles = `@scope (.${styles.document}) { ${pdfViewerStyles.replaceAll(':root', ':scope')} }`

export default function PdfDocumentPreview(props: { readonly url: string; readonly name: string }) {
  return <PdfPreview key={props.url} {...props} />
}

function PdfPreview({ url, name }: { readonly url: string; readonly name: string }) {
  const rootRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<HTMLDivElement>(null)
  useDocumentScrollActivity(containerRef)
  const inputRef = useRef<HTMLInputElement>(null)
  const composing = useRef(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [scale, setScale] = useState('page-width')
  const { page, pages, ready, error, result, search, closeSearch, setScale: applyScale, outline, activeOutlineId, navigateOutline } = usePdfViewer(url, containerRef, viewerRef)

  const openSearch = useCallback(() => {
    setSearchOpen(true)
    inputRef.current?.focus()
    inputRef.current?.select()
    if (query) search(query)
  }, [query, search])
  const hideSearch = useCallback(() => {
    setSearchOpen(false)
    closeSearch()
    containerRef.current?.focus({ preventScroll: true })
  }, [closeSearch])

  useEffect(() => {
    if (searchOpen) {
      inputRef.current?.focus()
      inputRef.current?.select()
    }
  }, [searchOpen])

  useDocumentSearchShortcuts({ root: rootRef, ready, searchOpen, openSearch, hideSearch })

  const resultText = !query ? '' : result.pending ? '검색 중…' : result.notFound ? '검색 결과 없음' : `${result.current} / ${result.total}개`
  return <div className={styles.document} ref={rootRef}>
    <style>{scopedViewerStyles}</style>
    <div className={styles.controls} aria-label="PDF 도구">
      <span>{pages > 0 ? `${page} / ${pages} 페이지` : 'PDF를 준비하는 중…'}</span>
      <label>크기 <select aria-label="PDF 크기" disabled={!ready} value={scale} onChange={(event) => {
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
            search(query, true, event.shiftKey)
          }
        }} />
      <span className={styles.result} role="status">{resultText}</span>
      <div className={styles.searchActions}>
        <button type="button" aria-label="이전 검색 결과" disabled={!query || result.total === 0}
          onClick={() => search(query, true, true)}>↑</button>
        <button type="button" aria-label="다음 검색 결과" disabled={!query || result.total === 0}
          onClick={() => search(query, true)}>↓</button>
        <button type="button" aria-label="검색 닫기" onClick={hideSearch}>닫기</button>
      </div>
    </div>}
    {error && <p role="alert" className={styles.error}>PDF를 표시하지 못했습니다. 다운로드해서 확인해 주세요.</p>}
    <div className={styles.viewport}>
      <div ref={containerRef} className={`${styles.scroll} ${scrollbarStyles.scrollbar}`} role="region" aria-label={`${name} 문서`} tabIndex={0}>
        <div ref={viewerRef} className="pdfViewer" />
      </div>
      {ready && !error && <DocumentOutline entries={outline} activeId={activeOutlineId} onNavigate={navigateOutline} />}
    </div>
  </div>
}
