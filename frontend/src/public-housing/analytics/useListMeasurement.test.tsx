import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { useListMeasurement } from './useListMeasurement'
vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(() => true), createAnalyticsId: () => crypto.randomUUID() }))
const capture = vi.mocked(captureProductEvent)
function Probe({ peek = false, detail = false }: { peek?: boolean; detail?: boolean }) {
  useListMeasurement(true, 'region_complex', 'test-key')
  return <><aside id="housing-list-page"><div id="housing-list-content" inert={peek} aria-hidden={peek}>
    <div data-testid="scroll" className="housing-results__scroll">목록</div>
  </div></aside>{detail && <dialog open className="housing-detail-layer" aria-modal="true" data-detail-visit-id="detail-1">상세</dialog>}</>
}
beforeEach(() => {
  capture.mockClear()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, width: 300, height: 300, top: 0, left: 0, right: 300, bottom: 300, toJSON: () => ({}) })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
const count = (name: string) => capture.mock.calls.filter(([event]) => event === name).length
it('peek 또는 모바일 상세 뒤 목록은 세지 않고 실제로 다시 보이면 새 방문을 시작한다', async () => {
  const view = render(<Probe peek />)
  await act(async () => {})
  expect(count('list_opened')).toBe(0)
  view.rerender(<Probe />)
  await waitFor(() => expect(count('list_opened')).toBe(1))
  const first = document.getElementById('housing-list-page')?.dataset.listViewId
  view.rerender(<Probe detail />)
  await waitFor(() => expect(count('list_closed')).toBe(1))
  fireEvent.wheel(view.getByTestId('scroll')); fireEvent.scroll(view.getByTestId('scroll'))
  expect(count('list_scrolled')).toBe(0)
  view.rerender(<Probe />)
  await waitFor(() => expect(count('list_opened')).toBe(2))
  expect(document.getElementById('housing-list-page')?.dataset.listViewId).not.toBe(first)
})
it('프로그램 스크롤을 제외하고 사용자 스크롤은 목록 방문당 한 번만 수집한다', async () => {
  const view = render(<Probe />)
  await waitFor(() => expect(count('list_opened')).toBe(1))
  fireEvent.scroll(view.getByTestId('scroll'))
  expect(count('list_scrolled')).toBe(0)
  fireEvent.wheel(view.getByTestId('scroll')); fireEvent.scroll(view.getByTestId('scroll')); fireEvent.scroll(view.getByTestId('scroll'))
  expect(count('list_scrolled')).toBe(1)
})
