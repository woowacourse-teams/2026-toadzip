import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent, setProductPageActive } from './productAnalytics'
import { ProductAnalyticsBoundary } from './ProductAnalyticsBoundary'

vi.mock('./productAnalytics', () => ({
  captureProductEvent: vi.fn(), setProductPageActive: vi.fn(), createAnalyticsId: () => crypto.randomUUID(),
}))

const consent = vi.hoisted(() => ({ allowed: true, listeners: new Set<() => void>() }))
vi.mock('../privacy/consentStore', () => ({
  analyticsCollectionAllowed: () => consent.allowed,
  consentStore: { subscribe: (listener: () => void) => { consent.listeners.add(listener); return () => consent.listeners.delete(listener) } },
}))

beforeEach(() => { vi.clearAllMocks(); consent.allowed = true; consent.listeners.clear() })

function Navigation() {
  const navigate = useNavigate()
  return <>
    <button onClick={() => void navigate('/mypage/notifications?tab=settings')}>설정 탭</button>
    <button onClick={() => void navigate('/mypage/private')}>다른 경로</button>
  </>
}

it('알림 관리 진입은 한 페이지뷰로 수집하고 쿼리 변경을 별도 방문으로 세지 않는다', () => {
  render(<MemoryRouter initialEntries={['/mypage/notifications']}>
    <ProductAnalyticsBoundary /><Navigation />
  </MemoryRouter>)
  expect(setProductPageActive).toHaveBeenLastCalledWith(true)
  expect(captureProductEvent).toHaveBeenCalledOnce()
  expect(captureProductEvent).toHaveBeenCalledWith('page_view', {}, { dedupeKey: expect.stringMatching(/^page:/) })
  fireEvent.click(screen.getByRole('button', { name: '설정 탭' }))
  expect(captureProductEvent).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '다른 경로' }))
  expect(setProductPageActive).toHaveBeenLastCalledWith(false)
  expect(captureProductEvent).toHaveBeenCalledOnce()
})


it('starts a fresh current-page visit only when consent becomes valid', () => {
  consent.allowed = false
  render(<MemoryRouter><ProductAnalyticsBoundary /></MemoryRouter>)
  expect(captureProductEvent).not.toHaveBeenCalled()
  act(() => { consent.allowed = true; consent.listeners.forEach(listener => listener()) })
  expect(captureProductEvent).toHaveBeenCalledOnce()
  act(() => { consent.allowed = false; consent.listeners.forEach(listener => listener()) })
  act(() => { consent.allowed = true; consent.listeners.forEach(listener => listener()) })
  expect(captureProductEvent).toHaveBeenCalledTimes(2)
  const keys = vi.mocked(captureProductEvent).mock.calls.map(([, , options]) => options?.dedupeKey)
  expect(new Set(keys).size).toBe(2)
})
