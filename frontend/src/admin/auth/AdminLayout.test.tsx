import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { AdminLayout } from './AdminLayout'

const logout = vi.fn()
vi.mock('./useAdminAuth', () => ({ useAdminAuth: () => ({ logout, session: { loginIdentifier: 'test-admin', role: 'ADMIN' } }) }))
beforeEach(() => logout.mockReset().mockResolvedValue(undefined))
function setup() {
  render(<MemoryRouter initialEntries={['/admin/notification-subscriptions']}><Routes>
    <Route path="/admin" element={<AdminLayout />}>
      <Route path="notification-subscriptions" element={<h1>알림 신청 관리</h1>} />
      <Route path="users" element={<h1>회원 관리</h1>} />
    </Route>
    <Route path="/admin/login" element={<h1>관리자 로그인</h1>} />
  </Routes></MemoryRouter>)
}
it('매일 하는 작업은 바로 보이고 수집 도구는 펼쳐서 찾을 수 있다', () => {
  setup()
  expect(screen.getByRole('link', { name: '검토할 데이터' })).toBeVisible()
  expect(screen.getByRole('link', { name: '원천 데이터' })).not.toBeVisible()
  fireEvent.click(screen.getByText('수집·원천 도구'))
  expect(screen.getByRole('link', { name: '원천 데이터' })).toBeVisible()
})
it('메뉴를 펼치고 닫을 수 있으며 Escape로 닫으면 버튼으로 포커스가 돌아간다', () => {
  setup()
  const button = screen.getByRole('button', { name: '메뉴' })
  expect(button).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(button)
  expect(button).toHaveAttribute('aria-expanded', 'true')
  const menu = screen.getByRole('navigation', { name: '관리자 메뉴' })
  expect(menu.id).toBe(button.getAttribute('aria-controls'))
  fireEvent.keyDown(menu, { key: 'Escape' })
  expect(button).toHaveAttribute('aria-expanded', 'false')
  expect(button).toHaveFocus()
})
it('접힌 메뉴에도 모든 링크의 아이콘과 접근 가능한 이름, 선택 상태를 남긴다', () => {
  setup()
  expect(screen.getByText('관리자 작업실')).toBeVisible()
  expect(screen.queryByText('공식 근거로 신뢰할 수 있는 정보를 만듭니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '메뉴 접기' }))
  const menu = screen.getByRole('navigation', { name: '관리자 메뉴' })
  const links = menu.querySelectorAll('a')
  expect(links).toHaveLength(14)
  links.forEach(link => {
    expect(link.querySelector('svg')).not.toBeNull()
    expect(link).toHaveAccessibleName()
  })
  expect(screen.getByRole('link', { name: '알림 신청 관리' })).toHaveAttribute('aria-current', 'page')
  const expand = screen.getByRole('button', { name: '메뉴 펼치기' })
  expect(expand).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(expand)
  expect(screen.getByRole('button', { name: '메뉴 접기' })).toHaveAttribute('aria-expanded', 'true')
})
it('메뉴에서 다른 페이지로 이동하면 메뉴를 닫고 현재 페이지를 표시한다', async () => {
  setup()
  fireEvent.click(screen.getByRole('button', { name: '메뉴' }))
  fireEvent.click(screen.getByRole('link', { name: '회원 관리' }))
  await screen.findByRole('heading', { name: '회원 관리' })
  expect(screen.getByRole('button', { name: '메뉴' })).toHaveAttribute('aria-expanded', 'false')
  expect(screen.getByRole('link', { name: '회원 관리' })).toHaveAttribute('aria-current', 'page')
})
it('로그아웃 실패는 알리고 다시 시도해 로그인 화면으로 이동할 수 있다', async () => {
  logout.mockRejectedValueOnce(new Error('로그아웃 연결 실패'))
  setup()
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃 연결 실패')
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  await waitFor(() => expect(screen.getByRole('heading', { name: '관리자 로그인' })).toBeVisible())
})
