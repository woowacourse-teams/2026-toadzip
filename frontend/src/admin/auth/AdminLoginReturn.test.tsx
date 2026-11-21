import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'
import { RequireAdmin } from './RequireAdmin'

vi.mock('./useAdminAuth', () => ({ useAdminAuth: () => ({ session: null, isLoading: false }) }))
function Where() {
  const location = useLocation()
  return <p>현재 화면 {location.pathname}{location.search}</p>
}
function setup(entry: string | { pathname: string; state: unknown }) {
  render(<MemoryRouter initialEntries={[entry]}><Routes>
    <Route path="/login" element={<Where />} />
    <Route path="/admin/login" element={<LoginPage />} />
    <Route path="/admin" element={<RequireAdmin />}><Route path="*" element={<Where />} /><Route index element={<Where />} /></Route>
  </Routes></MemoryRouter>)
}
it('세션이 없을 때 연 관리자 화면은 로그인 후 검색 조건과 화면 위치까지 그대로 돌아간다', async () => {
  setup('/admin/announcements/36/pdf-correction?failureCount=1&failureRow=AMBIGUOUS_HOUSING_TYPE%7C5%3A196771%3A1#rows')
  expect(await screen.findByText('현재 화면 /login?returnTo=' + encodeURIComponent('/admin/announcements/36/pdf-correction?failureCount=1&failureRow=AMBIGUOUS_HOUSING_TYPE%7C5%3A196771%3A1#rows'))).toBeVisible()
})

it('관리자 화면이 아닌 돌아갈 주소는 기본 화면으로 보낸다', async () => {
  setup({ pathname: '/admin/login', state: { from: '//example.com/admin' } })
  expect(await screen.findByText('현재 화면 /login?returnTo=%2Fadmin')).toBeVisible()
})
