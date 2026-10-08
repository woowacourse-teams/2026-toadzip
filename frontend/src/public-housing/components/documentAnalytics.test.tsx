import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { useDocumentPreviewTelemetry } from './documentAnalytics'

vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn() }))
let intersection: (entries: { target: Element; isIntersecting: boolean }[]) => void
const elements: HTMLElement[] = []
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: typeof intersection) { intersection = callback }
    observe() {}
    disconnect() {}
  })
})
afterEach(() => { for (const element of elements.splice(0)) element.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function page() { const element = document.createElement('div'); document.body.append(element); elements.push(element); return element }
function successCalls() { return vi.mocked(captureProductEvent).mock.calls.filter(([name]) => name === 'document_preview_succeeded') }

it('렌더된 쪽이 실제로 보이고 탭이 활성일 때만 한 번 성공한다', () => {
  const { result } = renderHook(() => useDocumentPreviewTelemetry('1', '2', 'open-3', 0))
  const element = page()
  result.current.requested()
  result.current.rendered(element)
  expect(successCalls()).toHaveLength(0)
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  act(() => intersection([{ target: element, isIntersecting: true }]))
  expect(successCalls()).toHaveLength(0)
  visibility.mockReturnValue('visible')
  act(() => document.dispatchEvent(new Event('visibilitychange')))
  act(() => intersection([{ target: element, isIntersecting: true }]))
  expect(successCalls()).toHaveLength(1)
  expect(successCalls()[0]).toEqual(['document_preview_succeeded', {
    announcement_id: '1', attachment_id: '2', document_open_id: 'open-3', preview_attempt: 0,
  }, { dedupeKey: 'open-3:0:succeeded' }])
})

it('다른 모달에 가려져 있으면 닫힌 뒤 처음 노출될 때까지 기다린다', async () => {
  const { result } = renderHook(() => useDocumentPreviewTelemetry('1', '2', 'open-3', 0))
  const element = page()
  const dialog = document.createElement('dialog'); dialog.open = true; document.body.append(dialog); elements.push(dialog)
  result.current.rendered(element)
  act(() => intersection([{ target: element, isIntersecting: true }]))
  expect(successCalls()).toHaveLength(0)
  dialog.remove()
  await waitFor(() => expect(successCalls()).toHaveLength(1))
})

it('오류가 난 렌더와 교체한 첨부의 늦은 콜백은 성공으로 집계하지 않는다', () => {
  const { result, rerender } = renderHook(({ attempt }) => useDocumentPreviewTelemetry('1', '2', 'open-3', attempt),
    { initialProps: { attempt: 0 } })
  const element = page()
  const previous = result.current
  previous.rendered(element)
  previous.failed('render_failed')
  act(() => intersection([{ target: element, isIntersecting: true }]))
  expect(successCalls()).toHaveLength(0)
  rerender({ attempt: 1 })
  previous.rendered(page())
  act(() => intersection([{ target: element, isIntersecting: true }]))
  expect(successCalls()).toHaveLength(0)
  const nextPage = page()
  result.current.rendered(nextPage)
  act(() => intersection([{ target: nextPage, isIntersecting: true }]))
  expect(successCalls()).toHaveLength(1)
})
