import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import HwpDocumentPreview from './HwpDocumentPreview.tsx'
import type { HwpResponse } from './hwpPreview.worker.ts'

class PreviewWorker {
  static instances: PreviewWorker[] = []
  onmessage: ((event: { data: HwpResponse }) => void) | null = null
  onerror: (() => void) | null = null
  postMessage = vi.fn()
  terminate = vi.fn()
  constructor() { PreviewWorker.instances.push(this) }
  send(data: unknown) { act(() => this.onmessage?.({ data: data as HwpResponse })) }
  ready(count = 8, outline?: unknown) { this.send({ type: 'ready', pages: Array.from({ length: count }, () => ({ width: 794, height: 1123 })), outline }) }
  page(page = 0) { this.send({ type: 'page', page, svg: '<svg/>', text: '<script>공고문</script>' }) }
  results(id: number, matches = [{ page: 4, rects: [{ x: 10, y: 20, width: 40, height: 12 }] }, { page: 6, rects: [{ x: 10, y: 30, width: 40, height: 12 }] }]) {
    this.send({ type: 'search', id, matches })
  }
}

beforeEach(() => {
  PreviewWorker.instances = []
  vi.useFakeTimers()
  vi.stubGlobal('Worker', PreviewWorker)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  let nextUrl = 0
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => `blob:hwp-${nextUrl++}`), revokeObjectURL: vi.fn() }))
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(818)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(600)

})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })
function ready() { const worker = PreviewWorker.instances.at(-1)!; worker.ready(); worker.page(); return worker }
function search(query = '임대', modifier = 'metaKey') {
  fireEvent.keyDown(screen.getByRole('region', { name: '공고.hwp 문서' }), { key: 'f', [modifier]: true })
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: query } })
  act(() => vi.advanceTimersByTime(250))
}

it('원본 개요가 없는 문서는 목차를 표시하지 않는다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  ready()
  expect(screen.queryByRole('button', { name: '목차' })).not.toBeInTheDocument()
})

it('원본 개요를 누르면 해당 문단으로 이동하고 스크롤 위치에 맞게 활성 항목을 바꾼다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = PreviewWorker.instances.at(-1)!
  worker.ready(8, [
    { id: '0:1', title: '첫 제목', depth: 0, pageNumber: 1, y: 161.7 },
    { id: '0:2', title: '둘째 제목', depth: 0, pageNumber: 2, y: 142.5 },
  ])
  const viewport = screen.getByRole('region', { name: '공고.hwp 문서' })
  fireEvent.click(screen.getByRole('button', { name: '목차' }))
  expect(screen.getByRole('navigation', { name: '문서 목차' })).toBeVisible()
  expect(screen.getByRole('button', { name: /첫 제목/ })).not.toHaveAttribute('aria-current')
  fireEvent.click(screen.getByRole('button', { name: /둘째 제목/ }))
  expect(viewport.scrollTop).toBeGreaterThan(1_000)
  expect(viewport).toHaveFocus()
  fireEvent.click(screen.getByRole('button', { name: '목차' }))
  expect(screen.getByRole('button', { name: /둘째 제목/ })).toHaveAttribute('aria-current', 'location')
  fireEvent.scroll(viewport, { target: { scrollTop: 0 } })
  expect(screen.getByRole('button', { name: /첫 제목/ })).not.toHaveAttribute('aria-current')
  fireEvent.scroll(viewport, { target: { scrollTop: 160 } })
  expect(screen.getByRole('button', { name: /첫 제목/ })).toHaveAttribute('aria-current', 'location')
})

it('파일을 바꾸면 이전 문서의 목차와 늦은 응답을 버린다', () => {
  const { rerender } = render(<HwpDocumentPreview url="blob:first" name="첫 문서.hwp" />)
  const first = PreviewWorker.instances.at(-1)!
  first.ready(2, [{ id: '0:1', title: '첫 제목', depth: 0, pageNumber: 1, y: 20 }])
  expect(screen.getByRole('button', { name: '목차' })).toBeInTheDocument()
  const late = first.onmessage
  rerender(<HwpDocumentPreview url="blob:second" name="둘째 문서.hwp" />)
  act(() => late?.({ data: { type: 'ready', pages: [{ width: 794, height: 1123 }], outline: [{ id: '0:1', title: '늦은 제목', depth: 0, pageNumber: 1, y: 20 }] } as HwpResponse }))
  expect(screen.queryByRole('button', { name: '목차' })).not.toBeInTheDocument()
  PreviewWorker.instances.at(-1)!.ready(2)
  expect(screen.queryByRole('button', { name: '목차' })).not.toBeInTheDocument()
})

it('첫 Escape는 목차를 닫고 두 번째 Escape는 검색만 닫는다', () => {
  render(<dialog open><HwpDocumentPreview url="blob:original" name="공고.hwp" /></dialog>)
  PreviewWorker.instances.at(-1)!.ready(2, [{ id: '0:1', title: '첫 제목', depth: 0, pageNumber: 1, y: 20 }])
  fireEvent.click(screen.getByRole('button', { name: '문서 검색' }))
  fireEvent.click(screen.getByRole('button', { name: '목차' }))
  fireEvent.keyDown(screen.getByRole('button', { name: /첫 제목/ }), { key: 'Escape' })
  expect(screen.getByRole('searchbox')).toBeInTheDocument()
  const toggle = screen.getByRole('button', { name: '목차' })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  fireEvent.keyDown(toggle, { key: 'Escape' })
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  expect(screen.getByRole('dialog')).toBeVisible()
})

it('확대된 문서에서 목차로 이동하면 가로 스크롤도 되돌린다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  PreviewWorker.instances.at(-1)!.ready(2, [{ id: '0:1', title: '첫 제목', depth: 0, pageNumber: 1, y: 20 }])
  const viewport = screen.getByRole('region', { name: '공고.hwp 문서' })
  fireEvent.change(screen.getByRole('combobox', { name: '한글 문서 크기' }), { target: { value: '200' } })
  viewport.scrollLeft = 400
  fireEvent.click(screen.getByRole('button', { name: '목차' }))
  fireEvent.click(screen.getByRole('button', { name: /첫 제목/ }))
  expect(viewport.scrollLeft).toBe(0)
})

it('여러 페이지를 세로로 배치하고 스크롤한 쪽수·확대를 반영하며 먼 이미지 URL을 해제한다', () => {
  const { unmount } = render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready(); worker.page(1)
  expect(screen.getByRole('region', { name: '공고.hwp 문서' })).toHaveAttribute('tabindex', '0')
  expect(screen.getAllByRole('region', { name: /페이지$/ })).toHaveLength(8)
  expect(screen.queryByRole('button', { name: '다음 페이지' })).not.toBeInTheDocument()
  expect(screen.getByRole('img', { name: '공고.hwp 1페이지' })).toBeVisible()
  fireEvent.scroll(screen.getByRole('region', { name: '공고.hwp 문서' }), { target: { scrollTop: 4550 } })
  expect(screen.getByText('5 / 8 페이지')).toBeVisible()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:hwp-0')
  expect(worker.postMessage).toHaveBeenCalledWith({ type: 'page', page: 4 })
  worker.page(4)
  fireEvent.change(screen.getByRole('combobox', { name: '한글 문서 크기' }), { target: { value: '150' } })
  expect(screen.getByRole('region', { name: '5페이지' })).toHaveStyle({ width: '1191px' })
  unmount()
  expect(worker.terminate).toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
})
it.each(['metaKey', 'ctrlKey'])('%s+F로 전체 검색·이동·강조하고 Enter/Shift+Enter로 순환한다', (modifier) => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready(); search('임대', modifier)
  expect(screen.getByRole('searchbox')).toHaveFocus()
  expect(worker.postMessage).toHaveBeenCalledWith({ type: 'search', query: '임대', id: expect.any(Number) })
  const id = worker.postMessage.mock.calls.at(-1)![0].id as number
  worker.results(id)
  expect(screen.getByText('1 / 2개')).toBeVisible()
  expect(screen.getByText('5 / 8 페이지')).toBeVisible()
  expect(screen.getByRole('region', { name: '5페이지' }).querySelector('[data-search-active="true"]')).toBeInTheDocument()
  fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' })
  expect(screen.getByText('2 / 2개')).toBeVisible()
  expect(screen.getByText('7 / 8 페이지')).toBeVisible()
  fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter', shiftKey: true })
  expect(screen.getByText('1 / 2개')).toBeVisible()
})
it('오래된 검색 결과를 무시하고 한글 조합 중에는 검색을 보내지 않는다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready(); search()
  const id = worker.postMessage.mock.calls.at(-1)![0].id as number
  const input = screen.getByRole('searchbox')
  fireEvent.compositionStart(input)
  fireEvent.change(input, { target: { value: 'ㅇ' } })
  act(() => vi.advanceTimersByTime(250))
  expect(worker.postMessage.mock.calls.filter(([request]) => request.type === 'search' && request.query === 'ㅇ')).toHaveLength(0)
  fireEvent.compositionEnd(input, { target: { value: '청년' } })
  act(() => vi.advanceTimersByTime(250))
  const nextId = worker.postMessage.mock.calls.at(-1)![0].id as number
  worker.results(id)
  expect(screen.queryByText('1 / 2개')).not.toBeInTheDocument()
  worker.results(nextId, [])
  expect(screen.getByText('검색 결과 없음')).toBeVisible()
})
it('Escape는 검색만 닫고 모달 밖 단축키는 유지한다', () => {
  render(<><button>모달 밖</button><dialog open><button>파일 목록</button><HwpDocumentPreview url="blob:original" name="공고.hwp" /></dialog></>)
  ready()
  const outside = new KeyboardEvent('keydown', { key: 'f', metaKey: true, bubbles: true, cancelable: true })
  fireEvent(screen.getByRole('button', { name: '모달 밖' }), outside)
  expect(outside.defaultPrevented).toBe(false)
  fireEvent.keyDown(screen.getByRole('button', { name: '파일 목록' }), { key: 'f', ctrlKey: true })
  const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
  fireEvent(screen.getByRole('searchbox'), escape)
  expect(escape.defaultPrevented).toBe(true)
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  expect(screen.getByRole('dialog')).toBeVisible()
})
it('파일 전환 시 이전 작업자·URL·검색 상태를 정리하고 늦은 응답을 무시한다', () => {
  const { rerender, unmount } = render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready(); search()
  const late = worker.onmessage
  rerender(<HwpDocumentPreview url="blob:new" name="공고.hwpx" />)
  expect(worker.terminate).toHaveBeenCalled()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:hwp-0')
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  act(() => late?.({ data: { type: 'page', page: 5, svg: '<svg/>' } as HwpResponse }))
  expect(screen.queryByRole('img')).not.toBeInTheDocument()
  unmount(); expect(vi.getTimerCount()).toBe(0)
})
it('암호·손상 등 SDK 오류와 시간 초과에 작업자를 정리하고 재시도한다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready()
  worker.send({ type: 'error' })
  expect(screen.getByRole('alert')).toHaveTextContent('다운로드해서 확인해 주세요')
  expect(worker.terminate).toHaveBeenCalled()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:hwp-0')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(PreviewWorker.instances).toHaveLength(2)
  act(() => vi.advanceTimersByTime(60_000))
  expect(PreviewWorker.instances[1]!.terminate).toHaveBeenCalled()
  expect(screen.getByRole('alert')).toBeVisible()
})

it('결과가 하나여도 다른 쪽을 읽다가 Enter를 누르면 검색 위치로 돌아온다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready(); search()
  worker.results(worker.postMessage.mock.calls.at(-1)![0].id as number, [{ page: 4, rects: [{ x: 10, y: 20, width: 40, height: 12 }] }])
  fireEvent.scroll(screen.getByRole('region', { name: '공고.hwp 문서' }), { target: { scrollTop: 0 } })
  expect(screen.getByText('1 / 8 페이지')).toBeVisible()
  fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' })
  expect(screen.getByText('5 / 8 페이지')).toBeVisible()
})
it('검색 후 직접 이동한 페이지에서 확대해도 이전 검색 위치로 튀지 않는다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  const worker = ready(); search()
  worker.results(worker.postMessage.mock.calls.at(-1)![0].id as number)
  fireEvent.scroll(screen.getByRole('region', { name: '공고.hwp 문서' }), { target: { scrollTop: 0 } })
  fireEvent.change(screen.getByRole('combobox'), { target: { value: '150' } })
  expect(screen.getByText('1 / 8 페이지')).toBeVisible()
})
it('현재 페이지 텍스트를 읽고 복사할 수 있으며 문서 마크업을 실행하지 않는다', () => {
  render(<HwpDocumentPreview url="blob:original" name="공고.hwp" />)
  ready()
  expect(screen.getByText('<script>공고문</script>')).toBeInTheDocument()
  expect(document.querySelector('script')).toBeNull()
})
