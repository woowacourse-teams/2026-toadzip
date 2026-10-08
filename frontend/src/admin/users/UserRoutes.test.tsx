import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../../App'

afterEach(() => vi.unstubAllGlobals())
const member = { id: 7, email: 'member@example.test', provider: 'KAKAO', createdAt: '2026-10-04T09:12:34' }
it('관리자 메뉴에서 회원 목록과 상세로 이동하며 직접 상세 접근도 지원한다', async () => {
  const fetchMock = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(String(input)).pathname
    const data = path === '/api/admin/auth/me' ? { loginIdentifier: 'admin', role: 'ADMIN' }
      : path === '/api/admin/users/7' ? { data: member }
      : { data: { items: [member], page: 0, hasNext: false, totalElements: 1, totalPages: 1 } }
    return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  const view = render(<MemoryRouter initialEntries={['/admin/users']}><App /></MemoryRouter>)
  const menu = await screen.findByRole('link', { name: '회원 관리' })
  expect(menu).toHaveAttribute('aria-current', 'page')
  fireEvent.click(await screen.findByRole('link', { name: '회원 7 상세 보기' }))
  expect(await screen.findByRole('table', { name: '회원 기본 정보' })).toBeVisible()
  expect(menu).toHaveAttribute('aria-current', 'page')
  fireEvent.click(screen.getByRole('link', { name: '목록으로' }))
  expect(await screen.findByRole('table', { name: '회원 목록' })).toBeVisible()
  view.unmount()
  render(<MemoryRouter initialEntries={['/admin/users/7']}><App /></MemoryRouter>)
  expect(await screen.findByRole('table', { name: '회원 기본 정보' })).toBeVisible()
})

it('미인증 회원 관리 접근은 로그인으로 보내고 회원 API를 호출하지 않는다', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: '인증이 필요합니다.' }), { status: 401 }))
  vi.stubGlobal('fetch', fetchMock)
  render(<MemoryRouter initialEntries={['/admin/users/7']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  expect(fetchMock.mock.calls.every(([url]) => String(url).endsWith('/api/admin/auth/me'))).toBe(true)
})
