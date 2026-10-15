import { act, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent, captureWebPageView, setProductPageActive } from './productAnalytics'
import { ProductAnalyticsBoundary } from './ProductAnalyticsBoundary'
import type { AnalyticsStopReason } from '../privacy/consentStore'

vi.mock('./productAnalytics', () => ({
  captureProductEvent: vi.fn(), captureWebPageView: vi.fn(() => true),
  setProductPageActive: vi.fn(), createAnalyticsId: () => crypto.randomUUID(),
}))

const consent = vi.hoisted(() => ({ allowed: true, listeners: new Set<() => void>(), stops: new Set<(reason: AnalyticsStopReason) => void>() }))
vi.mock('../privacy/consentStore', () => ({
  analyticsCollectionAllowed: () => consent.allowed,
  consentStore: {
    subscribe: (listener: () => void) => { consent.listeners.add(listener); return () => consent.listeners.delete(listener) },
    onStop: (listener: (reason: AnalyticsStopReason) => void) => { consent.stops.add(listener); return () => { consent.stops.delete(listener) } },
  },
}))

beforeEach(() => { vi.clearAllMocks(); consent.allowed = true; consent.listeners.clear(); consent.stops.clear() })

function Navigation() {
  const navigate = useNavigate()
  return <>
    <button onClick={() => void navigate('/mypage/notifications?tab=settings')}>설정 탭</button>
    <button onClick={() => void navigate('/mypage/private')}>다른 경로</button>
    <button onClick={() => void navigate('/explore')}>탐색</button>
    <button onClick={() => void navigate('/feedback')}>피드백</button>
    <button onClick={() => void navigate(-1)}>뒤로</button>
  </>
}

it('알림 관리 진입은 한 페이지뷰로 수집하고 쿼리 변경을 별도 방문으로 세지 않는다', () => {
  render(<MemoryRouter initialEntries={['/mypage/notifications']}>
    <ProductAnalyticsBoundary /><Navigation />
  </MemoryRouter>)
  expect(setProductPageActive).toHaveBeenLastCalledWith(true)
  expect(captureProductEvent).toHaveBeenCalledOnce()
  expect(captureWebPageView).toHaveBeenCalledOnce()
  expect(captureProductEvent).toHaveBeenCalledWith('page_view', {}, { dedupeKey: expect.stringMatching(/^page:/) })
  fireEvent.click(screen.getByRole('button', { name: '설정 탭' }))
  expect(captureProductEvent).toHaveBeenCalledOnce()
  expect(captureWebPageView).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '다른 경로' }))
  expect(setProductPageActive).toHaveBeenLastCalledWith(false)
  expect(captureProductEvent).toHaveBeenCalledOnce()
  expect(captureWebPageView).toHaveBeenCalledOnce()
})


it('starts a fresh current-page visit only when consent becomes valid', () => {
  consent.allowed = false
  render(<MemoryRouter initialEntries={['/explore']}><ProductAnalyticsBoundary /></MemoryRouter>)
  expect(captureProductEvent).not.toHaveBeenCalled()
  expect(captureWebPageView).not.toHaveBeenCalled()
  act(() => { consent.allowed = true; consent.listeners.forEach(listener => listener()) })
  expect(captureProductEvent).toHaveBeenCalledOnce()
  expect(captureWebPageView).toHaveBeenCalledOnce()
  act(() => { consent.allowed = false; consent.stops.forEach(stop => stop('reset')); consent.listeners.forEach(listener => listener()) })
  act(() => { consent.allowed = true; consent.listeners.forEach(listener => listener()) })
  expect(captureProductEvent).toHaveBeenCalledTimes(2)
  const keys = vi.mocked(captureProductEvent).mock.calls.map(([, , options]) => options?.dedupeKey)
  expect(new Set(keys).size).toBe(2)
  expect(captureWebPageView).toHaveBeenCalledTimes(2)
})


it('랜딩은 수집하지 않고 시작하기로 이동한 탐색 화면만 수집한다', () => {
  function Start() {
    const navigate = useNavigate()
    return <button onClick={() => void navigate('/explore')}>시작하기</button>
  }
  render(<MemoryRouter><ProductAnalyticsBoundary /><Start /></MemoryRouter>)
  expect(setProductPageActive).toHaveBeenLastCalledWith(false)
  expect(captureProductEvent).not.toHaveBeenCalled()
  expect(captureWebPageView).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '시작하기' }))
  expect(setProductPageActive).toHaveBeenLastCalledWith(true)
  expect(captureProductEvent).toHaveBeenCalledOnce()
  expect(captureWebPageView).toHaveBeenCalledOnce()
})

it('reuses the web visit key across StrictMode, rerenders and temporary consent pauses', () => {
  const tree = () => <StrictMode><MemoryRouter initialEntries={['/explore']}><ProductAnalyticsBoundary /></MemoryRouter></StrictMode>
  const { rerender } = render(tree())
  const first = vi.mocked(captureWebPageView).mock.calls[0][0]
  rerender(tree())
  act(() => { consent.allowed = false; consent.stops.forEach(stop => stop('pause')); consent.listeners.forEach(listener => listener()) })
  const pausedCalls = vi.mocked(captureWebPageView).mock.calls.length
  rerender(tree())
  expect(captureWebPageView).toHaveBeenCalledTimes(pausedCalls)
  act(() => { consent.allowed = true; consent.listeners.forEach(listener => listener()) })
  expect(captureWebPageView).toHaveBeenCalledOnce()
  expect(new Set(vi.mocked(captureWebPageView).mock.calls.map(([id]) => id))).toEqual(new Set([first]))
})

it('creates distinct web visits for pathname changes, history returns and allowed-page reentry', () => {
  render(<MemoryRouter initialEntries={['/explore']}><ProductAnalyticsBoundary /><Navigation /></MemoryRouter>)
  for (const name of ['피드백', '뒤로', '다른 경로', '탐색']) {
    fireEvent.click(screen.getByRole('button', { name }))
  }
  expect(captureWebPageView).toHaveBeenCalledTimes(4)
  expect(new Set(vi.mocked(captureWebPageView).mock.calls.map(([id]) => id)).size).toBe(4)
  expect(captureProductEvent).toHaveBeenCalledTimes(4)
})

it('creates a new visit on a fresh mount even on the same pathname', () => {
  const tree = <MemoryRouter initialEntries={['/explore']}><ProductAnalyticsBoundary /></MemoryRouter>
  const first = render(tree)
  first.unmount()
  render(tree)
  expect(captureWebPageView).toHaveBeenCalledTimes(2)
  expect(new Set(vi.mocked(captureWebPageView).mock.calls.map(([id]) => id)).size).toBe(2)
})
