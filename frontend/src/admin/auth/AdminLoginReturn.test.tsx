import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'
import { RequireAdmin } from './RequireAdmin'

const auth = vi.hoisted(() => ({ session: null as null | { loginIdentifier: string }, login: vi.fn() }))
vi.mock('./useAdminAuth', () => ({ useAdminAuth: () => ({ session: auth.session, isLoading: false, error: null, login: auth.login }) }))
beforeEach(() => {
  auth.session = null
  auth.login.mockReset().mockImplementation(async () => { auth.session = { loginIdentifier: 'admin' } })
})
function Where() {
  const location = useLocation()
  return <p>현재 화면 {location.pathname}{location.search}</p>
}
function setup(entry: string | { pathname: string; state: unknown }) {
  render(<MemoryRouter initialEntries={[entry]}><Routes>
    <Route path="/admin/login" element={<LoginPage />} />
    <Route path="/admin" element={<RequireAdmin />}><Route path="*" element={<Where />} /><Route index element={<Where />} /></Route>
  </Routes></MemoryRouter>)
}
async function login() {
  fireEvent.change(screen.getByLabelText(/로그인 식별자|아이디/), { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'secret' } })
  fireEvent.click(screen.getByRole('button', { name: '로그인' }))
}

it('세션이 없을 때 연 관리자 화면은 로그인 후 검색 조건까지 그대로 돌아간다', async () => {
  setup('/admin/announcements/36/pdf-correction?failureCount=1&failureRow=AMBIGUOUS_HOUSING_TYPE%7C5%3A196771%3A1')
  await login()
  expect(await screen.findByText('현재 화면 /admin/announcements/36/pdf-correction?failureCount=1&failureRow=AMBIGUOUS_HOUSING_TYPE%7C5%3A196771%3A1')).toBeVisible()
})

it('관리자 화면이 아닌 돌아갈 주소는 기본 화면으로 보낸다', async () => {
  setup({ pathname: '/admin/login', state: { from: '//example.com/admin' } })
  await login()
  expect(await screen.findByText('현재 화면 /admin')).toBeVisible()
})
