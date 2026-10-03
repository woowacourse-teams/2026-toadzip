import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PdfDocumentPreview from './PdfDocumentPreview.tsx'

const sdk = vi.hoisted(() => ({
  getDocument: vi.fn(), destroy: vi.fn(), reset: vi.fn(), localizeDestroy: vi.fn(),
  dispatch: vi.fn(), options: vi.fn(), scale: vi.fn(), navigate: vi.fn(), outline: vi.fn(),
}))
vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => ({
  AnnotationMode: { ENABLE: 1 }, GlobalWorkerOptions: {}, version: '6.3.289', getDocument: sdk.getDocument,
}))
// Canvas/worker rendering is checked in a real browser. This boundary double
// exercises our keyboard, search event and lifecycle contract with the SDK.
vi.mock('pdfjs-dist/legacy/web/pdf_viewer.mjs', () => {
  class EventBus {
    listeners = new Map<string, ((event: unknown) => void)[]>()
    on(name: string, listener: (event: unknown) => void) {
      this.listeners.set(name, [...this.listeners.get(name) ?? [], listener])
    }
    dispatch(name: string, event: unknown) {
      sdk.dispatch(name, event)
      for (const listener of this.listeners.get(name) ?? []) listener(event)
    }
  }
  return {
    EventBus, ScrollMode: { VERTICAL: 0 }, LinkTarget: { BLANK: 2 },
    FindState: { FOUND: 0, NOT_FOUND: 1, WRAPPED: 2, PENDING: 3 },
    PDFLinkService: class { setViewer() {} setDocument() {} goToDestination = sdk.navigate },
    PDFFindController: class {},
    PDFViewer: class {
      l10n = { destroy: sdk.localizeDestroy }
      pagesPromise = Promise.resolve()
      readonly options: { eventBus: EventBus; abortSignal: AbortSignal }
      constructor(options: { eventBus: EventBus; abortSignal: AbortSignal }) { this.options = options; sdk.options(options) }
      set currentScaleValue(value: string) { sdk.scale(value) }
      setDocument(document: { numPages: number } | null) {
        sdk.reset(document)
        if (document) this.options.eventBus.dispatch('pagesinit', {})
      }
    },
  }
})

beforeEach(() => {
  // React cleanup may run after file-local afterEach hooks. Clear calls at the
  // next test's start so the preceding document's destroy does not leak across tests.
  vi.clearAllMocks()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  sdk.destroy.mockResolvedValue(undefined)
  sdk.localizeDestroy.mockResolvedValue(undefined)
  sdk.navigate.mockResolvedValue(undefined)
  sdk.outline.mockResolvedValue(null)
  sdk.getDocument.mockReturnValue({ promise: Promise.resolve({ numPages: 32, getOutline: sdk.outline }), destroy: sdk.destroy })
})
afterEach(() => vi.unstubAllGlobals())

async function ready() { await screen.findByText('1 / 32 페이지') }
function eventBus() {
  return sdk.options.mock.calls.at(-1)![0].eventBus as { dispatch: (name: string, event: unknown) => void }
}

describe('PDF 연속 열람과 검색', () => {
  it('전체 문서를 세로 뷰어에 연결하고 스크롤한 페이지를 표시한다', async () => {
    const { unmount } = render(<PdfDocumentPreview url="blob:notice" name="공고문.pdf" />)
    await ready()
    expect(screen.getByRole('region', { name: '공고문.pdf 문서' })).toHaveAttribute('tabindex', '0')
    expect(screen.queryByRole('button', { name: '다음 페이지' })).not.toBeInTheDocument()
    act(() => eventBus().dispatch('pagechanging', { pageNumber: 12 }))
    expect(screen.getByText('12 / 32 페이지')).toBeVisible()
    fireEvent.change(screen.getByRole('combobox', { name: 'PDF 크기' }), { target: { value: '1.5' } })
    expect(sdk.scale).toHaveBeenLastCalledWith('1.5')
    const lifecycle = sdk.options.mock.calls[0]![0].abortSignal as AbortSignal
    unmount()
    expect(lifecycle.aborted).toBe(true)
    expect(sdk.reset).toHaveBeenLastCalledWith(null)
    expect(sdk.localizeDestroy).toHaveBeenCalledOnce()
    expect(sdk.destroy).toHaveBeenCalledOnce()
  })

  it.each(['metaKey', 'ctrlKey'])('%s+F는 모달 어디에서든 검색 입력에 포커스를 옮긴다', async (modifier) => {
    render(<dialog open><button>파일 목록</button><PdfDocumentPreview url="blob:notice" name="공고문.pdf" /></dialog>)
    await ready()
    const shortcut = new KeyboardEvent('keydown', { key: 'f', [modifier]: true, bubbles: true, cancelable: true })
    fireEvent(screen.getByRole('button', { name: '파일 목록' }), shortcut)
    expect(shortcut.defaultPrevented).toBe(true)
    expect(screen.getByRole('searchbox', { name: 'PDF 검색어' })).toHaveFocus()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '임대조건' } })
    expect(sdk.dispatch).toHaveBeenLastCalledWith('find', expect.objectContaining({
      query: '임대조건', highlightAll: true, type: '', findPrevious: false,
    }))
    act(() => eventBus().dispatch('updatefindcontrolstate', {
      state: 0, matchesCount: { current: 1, total: 3 }, rawQuery: '임대조건',
    }))
    expect(screen.getByText('1 / 3개')).toBeVisible()
    fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' })
    expect(sdk.dispatch).toHaveBeenLastCalledWith('find', expect.objectContaining({ type: 'again', findPrevious: false }))
    fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter', shiftKey: true })
    expect(sdk.dispatch).toHaveBeenLastCalledWith('find', expect.objectContaining({ type: 'again', findPrevious: true }))
  })

  it('검색 Escape는 모달을 닫지 않고 모달 밖 단축키를 가로채지 않는다', async () => {
    const outerKey = vi.fn()
    render(<div onKeyDown={outerKey}><button>모달 밖</button><dialog open>
      <PdfDocumentPreview url="blob:notice" name="공고문.pdf" />
    </dialog></div>)
    await ready()
    const outside = new KeyboardEvent('keydown', { key: 'f', metaKey: true, bubbles: true, cancelable: true })
    fireEvent(screen.getByRole('button', { name: '모달 밖' }), outside)
    expect(outside.defaultPrevented).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '문서 검색' }))
    outerKey.mockClear()
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    fireEvent(screen.getByRole('searchbox'), escape)
    expect(escape.defaultPrevented).toBe(true)
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeVisible()
    expect(outerKey).not.toHaveBeenCalled()
    expect(sdk.dispatch).toHaveBeenLastCalledWith('findbarclose', expect.any(Object))
  })

  it('한글 조합 중에는 검색을 보내지 않고 조합이 끝난 검색어로 검색한다', async () => {
    render(<PdfDocumentPreview url="blob:notice" name="공고문.pdf" />)
    await ready()
    fireEvent.click(screen.getByRole('button', { name: '문서 검색' }))
    const input = screen.getByRole('searchbox')
    fireEvent.compositionStart(input)
    sdk.dispatch.mockClear()
    fireEvent.change(input, { target: { value: 'ㅇ' } })
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(sdk.dispatch).not.toHaveBeenCalled()
    fireEvent.compositionEnd(input, { target: { value: '임대' } })
    expect(sdk.dispatch).toHaveBeenLastCalledWith('find', expect.objectContaining({ query: '임대' }))
  })

  it('검색 결과 없음과 빈 검색어를 구분하고 문서 전환 때 검색을 비운다', async () => {
    const { rerender } = render(<PdfDocumentPreview url="blob:one" name="공고문.pdf" />)
    await ready()
    fireEvent.click(screen.getByRole('button', { name: '문서 검색' }))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '없는단어' } })
    act(() => eventBus().dispatch('updatefindcontrolstate', { state: 1, matchesCount: { current: 0, total: 0 }, rawQuery: '없는단어' }))
    expect(screen.getByText('검색 결과 없음')).toBeVisible()
    expect(screen.getByRole('button', { name: '다음 검색 결과' })).toBeDisabled()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    expect(screen.queryByText('검색 결과 없음')).not.toBeInTheDocument()
    rerender(<PdfDocumentPreview url="blob:two" name="참고자료.pdf" />)
    await screen.findByRole('region', { name: '참고자료.pdf 문서' })
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(sdk.destroy).toHaveBeenCalled()
  })

  it('PDF 파싱 실패는 다운로드 안내를 표시한다', async () => {
    sdk.getDocument.mockImplementation(() => ({ promise: Promise.reject(new Error('invalid pdf')), destroy: sdk.destroy }))
    render(<PdfDocumentPreview url="blob:invalid" name="공고문.pdf" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('PDF를 표시하지 못했습니다')
  })

  it('SDK 초기화와 페이지 렌더 실패에도 다운로드 안내를 표시한다', async () => {
    sdk.options.mockImplementationOnce(() => { throw new Error('viewer initialization failed') })
    const { rerender } = render(<PdfDocumentPreview url="blob:initialization" name="공고문.pdf" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('다운로드해서 확인')
    rerender(<PdfDocumentPreview url="blob:rendering" name="공고문.pdf" />)
    await ready()
    act(() => eventBus().dispatch('pagerendered', { error: new Error('canvas failed') }))
    expect(screen.getByRole('alert')).toHaveTextContent('다운로드해서 확인')
  })

  it('빠른 파일 전환 뒤 도착한 이전 문서와 이벤트는 현재 문서를 덮어쓰지 않는다', async () => {
    let resolveOldDocument: (document: { numPages: number }) => void = () => {}
    const oldDocument = new Promise<{ numPages: number }>((resolve) => { resolveOldDocument = resolve })
    sdk.getDocument.mockReturnValueOnce({ promise: oldDocument, destroy: sdk.destroy })
    const { rerender } = render(<PdfDocumentPreview url="blob:old" name="이전.pdf" />)
    await waitFor(() => expect(sdk.getDocument).toHaveBeenCalledOnce())
    const oldEvents = eventBus()
    rerender(<PdfDocumentPreview url="blob:new" name="현재.pdf" />)
    await ready()
    await act(async () => {
      resolveOldDocument({ numPages: 9 })
      oldEvents.dispatch('pagechanging', { pageNumber: 7 })
    })
    expect(screen.getByText('1 / 32 페이지')).toBeVisible()
    expect(screen.queryByText('7 / 9 페이지')).not.toBeInTheDocument()
    expect(sdk.destroy).toHaveBeenCalledOnce()
  })
})


describe('PDF 목차 탐색', () => {
  it('내장 목차를 표시하고 클릭 목적지와 현재 스크롤 구간을 연결한다', async () => {
    sdk.outline.mockResolvedValue([
      { title: '공급 개요', dest: [1, { name: 'XYZ' }, 0, 700, null], items: [] },
      { title: '신청 자격', dest: [4, { name: 'Fit' }], items: [] },
    ])
    render(<PdfDocumentPreview url="blob:outline" name="공고문.pdf" />)
    fireEvent.click(await screen.findByRole('button', { name: '목차' }))
    const first = screen.getByRole('button', { name: /공급 개요/ })
    expect(first).not.toHaveAttribute('aria-current')
    act(() => eventBus().dispatch('updateviewarea', { location: { pageNumber: 3, top: 700 } }))
    expect(first).toHaveAttribute('aria-current', 'location')
    fireEvent.click(screen.getByRole('button', { name: /신청 자격/ }))
    expect(sdk.navigate).toHaveBeenCalledWith([4, { name: 'Fit' }])
    await waitFor(() => expect(screen.getByRole('region', { name: '공고문.pdf 문서' })).toHaveFocus())
  })

  it.each(['empty', 'failure'])('목차가 없거나 추출에 실패해도 문서는 계속 열람한다: %s', async (kind) => {
    if (kind === 'failure') sdk.outline.mockRejectedValue(new Error('Invalid outline'))
    render(<PdfDocumentPreview url="blob:no-outline" name="공고문.pdf" />)
    await ready()
    await waitFor(() => expect(sdk.outline).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: '목차' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '문서 검색' })).toBeEnabled()
  })

  it('파일 전환 뒤 늦게 도착한 이전 목차를 표시하지 않는다', async () => {
    let resolveOutline: (value: unknown) => void = () => {}
    sdk.outline.mockReturnValueOnce(new Promise((resolve) => { resolveOutline = resolve }))
    const { rerender } = render(<PdfDocumentPreview url="blob:old-outline" name="이전.pdf" />)
    await waitFor(() => expect(sdk.outline).toHaveBeenCalledOnce())
    rerender(<PdfDocumentPreview url="blob:new-outline" name="현재.pdf" />)
    await screen.findByRole('region', { name: '현재.pdf 문서' })
    await act(async () => resolveOutline([{ title: '이전 목차', dest: [0, { name: 'Fit' }], items: [] }]))
    expect(screen.queryByRole('button', { name: '목차' })).not.toBeInTheDocument()
  })
})

it('검색과 목차가 열렸을 때 Escape는 목차 다음 검색 순서로 닫는다', async () => {
  sdk.outline.mockResolvedValue([{ title: '개요', dest: [0, { name: 'Fit' }], items: [] }])
  render(<dialog open><PdfDocumentPreview url="blob:escape" name="공고문.pdf" /></dialog>)
  const toggle = await screen.findByRole('button', { name: '목차' })
  fireEvent.click(screen.getByRole('button', { name: '문서 검색' }))
  fireEvent.click(toggle)
  fireEvent.keyDown(toggle, { key: 'Escape' })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(screen.getByRole('searchbox')).toBeVisible()
  const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
  fireEvent(toggle, escape)
  expect(escape.defaultPrevented).toBe(true)
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  expect(screen.getByRole('dialog')).toBeVisible()
})

it.each([
  { key: 'f', ctrlKey: true, altKey: true },
  { key: 'f', metaKey: true, isComposing: true },
  { key: 'g', ctrlKey: true },
])('문서 검색 대상이 아닌 단축키는 브라우저에 남긴다: %j', async (key) => {
  render(<dialog open><button>파일 목록</button><PdfDocumentPreview url="blob:shortcuts" name="공고.pdf" /></dialog>)
  await ready()
  const event = new KeyboardEvent('keydown', { ...key, bubbles: true, cancelable: true })
  fireEvent(screen.getByRole('button', { name: '파일 목록' }), event)
  expect(event.defaultPrevented).toBe(false)
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
})

it('문서 준비 전과 뷰어 제거 후에는 검색 단축키를 가로채지 않는다', async () => {
  sdk.getDocument.mockReturnValue({ promise: new Promise(() => {}), destroy: sdk.destroy })
  const { rerender } = render(<dialog open><button>파일 목록</button><PdfDocumentPreview url="blob:pending" name="공고.pdf" /></dialog>)
  const before = new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true, cancelable: true })
  fireEvent(screen.getByRole('button', { name: '파일 목록' }), before)
  expect(before.defaultPrevented).toBe(false)
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  rerender(<dialog open><button>파일 목록</button></dialog>)
  const after = new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true, cancelable: true })
  fireEvent(screen.getByRole('button', { name: '파일 목록' }), after)
  expect(after.defaultPrevented).toBe(false)
})
