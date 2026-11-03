import type { DocumentPreviewTelemetry } from './documentAnalytics'
import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { AnnotationMode, getDocument, GlobalWorkerOptions, version, type PDFDocumentProxy, type PDFDocumentLoadingTask } from 'pdfjs-dist/legacy/build/pdf.mjs'
import type { EventBus, PDFViewer, PDFLinkService } from 'pdfjs-dist/legacy/web/pdf_viewer.mjs'
import { matchesInContext, type PdfFindDiffs, type PdfTextItem } from './pdfFindContext.ts'
import { activePdfOutlineId, readPdfOutline, type PdfLocation, type PdfOutlineEntry } from './pdfOutline.ts'
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

export function usePdfViewer(url: string, containerRef: RefObject<HTMLDivElement | null>, viewerRef: RefObject<HTMLDivElement | null>, telemetry?: DocumentPreviewTelemetry) {
  const [page, setPage] = useState(1)
  const [pages, setPages] = useState(0)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState(false)
  const [result, setResult] = useState(emptyResult)
  const [outline, setOutline] = useState<readonly PdfOutlineEntry[]>([])
  const [location, setLocation] = useState<PdfLocation>({ pageNumber: 1, top: Infinity })
  const runtime = useRef<{ viewer: PDFViewer; events: EventBus; linkService: PDFLinkService; findController: { _dirtyMatch?: boolean } } | null>(null)
  const queryRef = useRef<string | string[]>('')
  const searchPageRef = useRef<number | null>(null)
  const contextRef = useRef<readonly string[] | null>(null)
  const documentRef = useRef<PDFDocumentProxy | null>(null)
  // 문맥 검색은 줄 구분(hasEOL)이 필요한데 pdf.js find 내용에는 줄바꿈이 없어, 근거 쪽의 텍스트 항목을 따로 읽어 둔다.
  const pageItemsRef = useRef(new Map<number, readonly PdfTextItem[]>())
  const searchIdRef = useRef(0)
  const scaleRef = useRef('page-width')

  useEffect(() => {
    const container = containerRef.current
    const element = viewerRef.current
    if (!container || !element) return
    const lifecycle = new AbortController()
    let viewer: PDFViewer | undefined
    let task: PDFDocumentLoadingTask | undefined
    let resize: ResizeObserver | undefined
    const fail = () => { if (!lifecycle.signal.aborted) { setError(true); telemetry?.failed('render_failed') } }

    async function open() {
      // The legacy build above initializes pdfjsLib, which the web viewer reads
      // during module evaluation. Keep this import after that initialization.
      const { PDFViewer, EventBus, PDFLinkService, PDFFindController, FindState, ScrollMode, LinkTarget } = await import('pdfjs-dist/legacy/web/pdf_viewer.mjs')
      if (lifecycle.signal.aborted || !container || !element) return
      const events = new EventBus()
      const linkService = new PDFLinkService({ eventBus: events, externalLinkTarget: LinkTarget.BLANK,
        externalLinkRel: 'noopener noreferrer', ignoreDestinationZoom: true })
      // Restrict evidence highlighting to its stated page. A normal find may
      // otherwise navigate to the same number on an unrelated page. Matches stay
      // on that page, so scrolling to the match brings the evidence into view.
      class EvidenceFindController extends PDFFindController {
        override match(query: string | string[], content: string, pageIndex: number) {
          if (searchPageRef.current !== null && pageIndex + 1 !== searchPageRef.current) return []
          // 근거 문맥(주택형·면적)이 있으면 같은 줄에 그 표기가 있는 값만 강조한다. 없으면 아무것도 강조하지 않는다.
          const matches = super.match(query, content, pageIndex)
          if (!matches || !contextRef.current?.length) return matches
          const items = pageItemsRef.current.get(pageIndex)
          const diffs = (this as unknown as { _pageDiffs: PdfFindDiffs[] })._pageDiffs[pageIndex]
          return items ? matchesInContext(items, diffs, matches, contextRef.current) : []
        }
      }
      const findController = new EvidenceFindController({ eventBus: events, linkService })
      // 6.3.289 supports abortSignal at runtime; its generated options type omits it.
      const options = { container, viewer: element, eventBus: events, linkService, findController,
        abortSignal: lifecycle.signal, annotationMode: AnnotationMode.ENABLE, maxCanvasPixels: 16_000_000 }
      viewer = new PDFViewer(options)
      viewer.scrollMode = ScrollMode.VERTICAL
      linkService.setViewer(viewer)
      runtime.current = { viewer, events, linkService, findController: findController as unknown as { _dirtyMatch?: boolean } }
      events.on('pagesinit', () => {
        if (lifecycle.signal.aborted || !viewer) return
        viewer.currentScaleValue = scaleRef.current
        setReady(true)
      })
      events.on('pagechanging', ({ pageNumber }: { pageNumber: number }) => {
        if (!lifecycle.signal.aborted) setPage(pageNumber)
      })
      events.on('updateviewarea', ({ location: position }: { location: PdfLocation }) => {
        if (!lifecycle.signal.aborted) setLocation({ pageNumber: position.pageNumber, top: position.top })
      })
      events.on('pagerendered', ({ error: renderError, source }: { error?: unknown; source?: { div?: unknown } }) => {
        if (lifecycle.signal.aborted) return
        if (renderError) fail()
        else if (source?.div instanceof HTMLElement) telemetry?.rendered(source.div)
      })
      events.on('updatefindmatchescount', ({ matchesCount }: { matchesCount: { current: number; total: number } }) => {
        if (!lifecycle.signal.aborted && queryRef.current) setResult((previous) => ({ ...previous, ...matchesCount }))
      })
      events.on('updatefindcontrolstate', ({ state, matchesCount, rawQuery }: {
        state: number; matchesCount: { current: number; total: number }; rawQuery: string | string[]
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
      documentRef.current = document
      setPages(document.numPages)
      linkService.setDocument(document)
      viewer.setDocument(document)
      // Outline metadata is optional. Its absence or failure must not block rendering.
      void readPdfOutline(document).then((entries) => {
        if (!lifecycle.signal.aborted) setOutline(entries)
      }).catch(() => {})
      const pagesReady: Promise<unknown> | undefined = viewer.pagesPromise
      void pagesReady?.catch(fail)
    }
    void open().catch(fail)
    return () => {
      runtime.current = null
      documentRef.current = null
      pageItemsRef.current = new Map()
      resize?.disconnect()
      // setDocument(null) is the SDK's reset API; 6.3.289's generated type
      // omits null. It cancels renders, search timers and per-document listeners.
      if (viewer) (viewer.setDocument as (document: PDFDocumentProxy | null) => void)(null)
      lifecycle.abort()
      void viewer?.l10n?.destroy().catch(() => {})
      void task?.destroy().catch(() => {})
    }
  }, [url, containerRef, viewerRef, telemetry])

  // 검색어 여러 개(근거 금액의 원·천원·만원 표기)는 그중 하나와 맞으면 되고, 다른 숫자 안에서 맞지 않게 낱말 단위로 찾는다.
  const search = useCallback((query: string | readonly string[], again = false, previous = false, pageNumber: number | null = null, context: readonly string[] | null = null) => {
    const id = ++searchIdRef.current
    const dispatch = () => {
      if (id !== searchIdRef.current) return
      searchPageRef.current = pageNumber
      // 같은 검색어라도 쪽·문맥이 다를 수 있고, 같은 근거를 다시 눌러도 그 위치로 다시 가도록 이전 결과를 버린다.
      if (!again && runtime.current) runtime.current.findController._dirtyMatch = true
      contextRef.current = context
      const terms = typeof query === 'string' ? query : [...query]
      queryRef.current = terms
      if (!terms.length) setResult(emptyResult)
      runtime.current?.events.dispatch('find', {
        source: containerRef.current, type: again ? 'again' : '', query: terms,
        caseSensitive: false, entireWord: typeof terms !== 'string', highlightAll: true,
        findPrevious: previous, matchDiacritics: false,
      })
    }
    const document = documentRef.current
    if (!context?.length || !document || pageNumber === null || pageItemsRef.current.has(pageNumber - 1)) { dispatch(); return }
    // pdf.js find와 같은 옵션으로 읽어야 diffs가 가리키는 글자 위치가 맞는다. 읽지 못하면 문맥 확인을 못 하므로 강조하지 않는다.
    void document.getPage(pageNumber).then(page => page.getTextContent({ disableNormalization: true })).then(content => {
      if (documentRef.current !== document) return
      pageItemsRef.current.set(pageNumber - 1, content.items.flatMap(item => 'str' in item ? [{ str: item.str, hasEOL: item.hasEOL }] : []))
    }).catch(() => {}).finally(dispatch)
  }, [containerRef])
  const closeSearch = useCallback(() => {
    runtime.current?.events.dispatch('findbarclose', { source: containerRef.current })
  }, [containerRef])
  const setScale = useCallback((value: string) => {
    scaleRef.current = value
    if (runtime.current) runtime.current.viewer.currentScaleValue = value
  }, [])
  const goToPage = useCallback((pageNumber: number) => {
    const current = runtime.current
    if (!current || !Number.isFinite(pageNumber)) return
    current.viewer.currentPageNumber = Math.min(Math.max(1, Math.trunc(pageNumber)), current.viewer.pagesCount)
  }, [])
  const navigateOutline = useCallback((id: string) => {
    const entry = outline.find((item) => item.id === id)
    const current = runtime.current
    if (!entry || !current) return
    void current.linkService.goToDestination(entry.destination).then(() => {
      if (runtime.current === current) containerRef.current?.focus({ preventScroll: true })
    }).catch(() => {})
  }, [outline, containerRef])
  const activeOutlineId = activePdfOutlineId(outline, location)
  return { page, pages, ready, error, result, search, closeSearch, setScale, goToPage, outline, activeOutlineId, navigateOutline }
}
