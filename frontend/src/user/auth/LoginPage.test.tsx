import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'
import { getCurrentUser } from './api'
import { readLoginReturn, rememberLoginReturn } from './loginReturn'

vi.mock('./api', () => ({ getCurrentUser: vi.fn(), socialLoginUrl: () => '#oauth' }))
vi.mock('../../privacy/usePrivacy', () => ({
  usePrivacyNotices: () => ({ notices: [], error: false, retry: vi.fn() }),
}))
vi.mock('../../privacy/consentStore', () => ({ consentStore: { beginAuthTransition: vi.fn() } }))
vi.mock('../../analytics/productAnalytics', () => ({ setReplaySensitive: vi.fn(), captureProductEvent: vi.fn() }))
beforeEach(() => { vi.mocked(getCurrentUser).mockResolvedValue(null) })
afterEach(() => { sessionStorage.clear(); vi.clearAllMocks() })

function Destination() {
  const location = useLocation()
  return <output aria-label="복귀 주소">{location.pathname}{location.search}{location.hash}</output>
}
function open(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="*" element={<Destination />} />
  </Routes></MemoryRouter>)
}

it('복귀 정보 없이 직접 진입하거나 외부 주소를 주면 지도로 돌아간다', () => {
  open('/login?returnTo=https://example.com')
  fireEvent.click(screen.getByRole('link', { name: '지도로 돌아가기' }))
  expect(screen.getByLabelText('복귀 주소')).toHaveTextContent('/explore')
})

it('제공자를 선택하면 새로고침과 OAuth 왕복을 위한 주소를 보존한다', () => {
  open('/login?returnTo=%2Fexplore%3FmapLat%3D37.5%26mapLng%3D127%26mapZoom%3D12%23map')
  fireEvent.click(screen.getByRole('link', { name: 'Google로 로그인' }))
  expect(readLoginReturn(true)).toBe('/explore?mapLat=37.5&mapLng=127&mapZoom=12#map')
})

it('로그인 완료 후 저장된 주소로 복귀하고 한 번 소비한다', async () => {
  rememberLoginReturn('/explore?boundaryRegionCode=11440&mapZoom=12#map', true)
  vi.mocked(getCurrentUser).mockResolvedValue({ id: 7, email: null })
  open('/login')
  await waitFor(() => expect(screen.getByLabelText('복귀 주소')).toHaveTextContent('/explore?boundaryRegionCode=11440&mapZoom=12#map'))
  expect(readLoginReturn()).toBeNull()
})

it('알림 로그인 요청을 취소하면 재로그인 요청 없이 지도로 돌아간다', () => {
  open('/login?returnTo=%2Fexplore%3Finbox%3Dopen%26boundaryRegionCode%3D11440')
  fireEvent.click(screen.getByRole('link', { name: '지도로 돌아가기' }))
  expect(screen.getByLabelText('복귀 주소')).toHaveTextContent('/explore?boundaryRegionCode=11440')
})
