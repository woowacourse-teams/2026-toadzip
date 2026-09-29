import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { AnnotationMode, getDocument, GlobalWorkerOptions, version, type PDFDocumentProxy, type PDFDocumentLoadingTask } from 'pdfjs-dist/legacy/build/pdf.mjs'
import type { EventBus, PDFViewer } from 'pdfjs-dist/legacy/web/pdf_viewer.mjs'
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'

// Bypass immutable responses cached before Nginx served .mjs as JavaScript.
const workerSource = new URL(workerUrl, window.location.href)
workerSource.searchParams.set('mime', 'javascript')
GlobalWorkerOptions.workerSrc = workerSource.href
const assets = `${import.meta.env.BASE_URL}pdfjs-${version}/`
export interface PdfSearchResult {
  readonly current: number
  readonly total: number
  readonly pending: boolean
  readonly notFound: boolean
}
const emptyResult: PdfSearchResult = { current: 0, total: 0, pending: false, notFound: false }

export function usePdfViewer(url: string, containerRef: RefObject<HTMLDivElement | null>, viewerRef: RefObject<HTMLDivElement | null>) {
  const [page, setPage] = useState(1)
  const [pages, setPages] = useState(0)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState(false)
  const [result, setResult] = useState(emptyResult)
  const runtime = useRef<{ viewer: PDFViewer; events: EventBus } | null>(null)
  const queryRef = useRef('')
  const scaleRef = useRef('page-width')

  useEffect(() => {
    const container = containerRef.current
    const element = viewerRef.current
    if (!container || !element) return
    const lifecycle = new AbortController()
    let viewer: PDFViewer | undefined
    let task: PDFDocumentLoadingTask | undefined
    let resize: ResizeObserver | undefined
    const fail = () => { if (!lifecycle.signal.aborted) setError(true) }

    async function open() {
      // The legacy build above initializes pdfjsLib, which the web viewer reads
      // during module evaluation. Keep this import after that initialization.
      const { PDFViewer, EventBus, PDFLinkService, PDFFindController, FindState, ScrollMode, LinkTarget } = await import('pdfjs-dist/legacy/web/pdf_viewer.mjs')
      if (lifecycle.signal.aborted || !container || !element) return
      const events = new EventBus()
      const linkService = new PDFLinkService({ eventBus: events, externalLinkTarget: LinkTarget.BLANK,
        externalLinkRel: 'noopener noreferrer', ignoreDestinationZoom: true })
      const findController = new PDFFindController({ eventBus: events, linkService })
      // 6.3.289 supports abortSignal at runtime; its generated options type omits it.
      const options = { container, viewer: element, eventBus: events, linkService, findController,
        abortSignal: lifecycle.signal, annotationMode: AnnotationMode.ENABLE, maxCanvasPixels: 16_000_000 }
      viewer = new PDFViewer(options)
      viewer.scrollMode = ScrollMode.VERTICAL
      linkService.setViewer(viewer)
      runtime.current = { viewer, events }
      events.on('pagesinit', () => {
        if (lifecycle.signal.aborted || !viewer) return
        viewer.currentScaleValue = scaleRef.current
        setReady(true)
      })
      events.on('pagechanging', ({ pageNumber }: { pageNumber: number }) => {
        if (!lifecycle.signal.aborted) setPage(pageNumber)
      })
      events.on('pagerendered', ({ error: renderError }: { error?: unknown }) => { if (renderError) fail() })
      events.on('updatefindmatchescount', ({ matchesCount }: { matchesCount: { current: number; total: number } }) => {
        if (!lifecycle.signal.aborted && queryRef.current) setResult((previous) => ({ ...previous, ...matchesCount }))
      })
      events.on('updatefindcontrolstate', ({ state, matchesCount, rawQuery }: {
        state: number; matchesCount: { current: number; total: number }; rawQuery: string
      }) => {
        if (!lifecycle.signal.aborted && rawQuery === queryRef.current && rawQuery) {
          setResult({ ...matchesCount, pending: state === FindState.PENDING, notFound: state === FindState.NOT_FOUND })
        }
      })
      let lastWidth = container.clientWidth
      resize = new ResizeObserver(() => {
        const width = container.clientWidth
        if (width === lastWidth) return
        lastWidth = width
        if (viewer && scaleRef.current === 'page-width') viewer.currentScaleValue = 'page-width'
      })
      resize.observe(container)
      task = getDocument({ url, cMapUrl: `${assets}cmaps/`, cMapPacked: true,
        standardFontDataUrl: `${assets}standard_fonts/`, wasmUrl: `${assets}wasm/` })
      const document = await task.promise
      if (lifecycle.signal.aborted) return
      setPages(document.numPages)
      linkService.setDocument(document)
      viewer.setDocument(document)
      const pagesReady: Promise<unknown> | undefined = viewer.pagesPromise
      void pagesReady?.catch(fail)
    }
    void open().catch(fail)
    return () => {
      runtime.current = null
      resize?.disconnect()
      // setDocument(null) is the SDK's reset API; 6.3.289's generated type
      // omits null. It cancels renders, search timers and per-document listeners.
      if (viewer) (viewer.setDocument as (document: PDFDocumentProxy | null) => void)(null)
      lifecycle.abort()
      void viewer?.l10n?.destroy().catch(() => {})
      void task?.destroy().catch(() => {})
    }
  }, [url, containerRef, viewerRef])

  const search = useCallback((query: string, again = false, previous = false) => {
    queryRef.current = query
    if (!query) setResult(emptyResult)
    runtime.current?.events.dispatch('find', {
      source: containerRef.current, type: again ? 'again' : '', query,
      caseSensitive: false, entireWord: false, highlightAll: true,
      findPrevious: previous, matchDiacritics: false,
    })
  }, [containerRef])
  const closeSearch = useCallback(() => {
    runtime.current?.events.dispatch('findbarclose', { source: containerRef.current })
  }, [containerRef])
  const setScale = useCallback((value: string) => {
    scaleRef.current = value
    if (runtime.current) runtime.current.viewer.currentScaleValue = value
  }, [])
  return { page, pages, ready, error, result, search, closeSearch, setScale }
}
