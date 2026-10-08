import { StrictMode } from 'react'
import { act, cleanup, render, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DetailLocationResult } from '../navigation/detailLocation'
import { useProductDetailAnalytics } from './useProductDetailAnalytics'
import { captureProductEvent } from '../../analytics/productAnalytics'

vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(() => true), createAnalyticsId: () => crypto.randomUUID() }))
const capture = vi.mocked(captureProductEvent)
const rectangle = { x: 10, y: 10, top: 10, left: 10, bottom: 300, right: 400, width: 390, height: 290, toJSON: () => ({}) }
function Probe({ location, ready }: { location: DetailLocationResult; ready: string | null }) {
  useProductDetailAnalytics({ detailLocation: location, readyComplexId: ready, readyAnnouncementId: null })
  return location.kind === 'complex' ? <dialog open className="housing-detail-layer" aria-label="단지 상세"><h2>상세</h2></dialog> : null
}
const wrap = ({ children }: { children: React.ReactNode }) => <StrictMode><MemoryRouter>{children}</MemoryRouter></StrictMode>
beforeEach(() => {
  capture.mockClear()
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rectangle)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
})
afterEach(() => { cleanup(); document.querySelectorAll('[data-test-blocker]').forEach(node => node.remove()); vi.restoreAllMocks() })

describe('제품 활성 상세 계측', () => {
  it('로드 성공이 다른 팝업 뒤에 있으면 기다리고, 노출 후 재렌더와 반응형 재열기는 한 번만 센다', async () => {
    const blocker = document.createElement('dialog'); blocker.open = true; blocker.dataset.testBlocker = 'true'; document.body.append(blocker)
    const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    blocker.remove()
    await waitFor(() => expect(capture).toHaveBeenCalledTimes(1))
    expect(capture).toHaveBeenLastCalledWith('view_complex', expect.objectContaining({ complex_id: '12', entry_point: 'direct' }), expect.any(Object))
    const dialog = document.querySelector('dialog.housing-detail-layer') as HTMLDialogElement
    const visit = dialog.dataset.detailVisitId
    await act(async () => { dialog.open = false; dialog.open = true })
    view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />)
    expect(capture).toHaveBeenCalledTimes(1)
    expect(dialog.dataset.detailVisitId).toBe(visit)
  })
  it('잘못된 타깃 응답과 백그라운드를 세지 않고 닫고 재열면 새 방문으로 센다', async () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="11" />, { wrapper: wrap })
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />)
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    visibility.mockReturnValue('visible')
    await act(async () => document.dispatchEvent(new Event('visibilitychange')))
    expect(capture).toHaveBeenCalledTimes(1)
    const first = capture.mock.calls[0][1]?.detail_visit_id
    view.rerender(<Probe location={{ kind: 'none' }} ready={null} />)
    view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />)
    await waitFor(() => expect(capture).toHaveBeenCalledTimes(2))
    expect(capture.mock.calls[1][1]?.detail_visit_id).not.toBe(first)
  })
  it('뷰포트 밖 또는 크기 없는 상세는 활성으로 세지 않는다', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ ...rectangle, width: 0, height: 0 })
    render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rectangle)
    await act(async () => window.dispatchEvent(new Event('resize')))
    expect(capture).toHaveBeenCalledTimes(1)
  })
})
