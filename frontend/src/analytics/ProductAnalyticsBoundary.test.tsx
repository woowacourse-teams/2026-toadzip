import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent, setProductPageActive } from './productAnalytics'
import { ProductAnalyticsBoundary } from './ProductAnalyticsBoundary'

vi.mock('./productAnalytics', () => ({
  captureProductEvent: vi.fn(), setProductPageActive: vi.fn(), createAnalyticsId: () => crypto.randomUUID(),
}))

beforeEach(() => vi.clearAllMocks())

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
