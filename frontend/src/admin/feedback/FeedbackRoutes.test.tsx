import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../../App'

afterEach(() => vi.unstubAllGlobals())
const entry = { id: 7, content: '지도 개선 의견\n두 번째 줄', createdAt: '2026-10-07T01:00:00Z' }

it('관리자 메뉴에서 피드백 목록과 전체 내용을 확인한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
    const body = String(input).endsWith('/api/admin/auth/me') ? { loginIdentifier: 'admin', role: 'ADMIN' }
      : { data: { items: [entry], page: 0, hasNext: false, totalElements: 1, totalPages: 1 } }
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
  }))
  render(<MemoryRouter initialEntries={['/admin/feedback']}><App /></MemoryRouter>)
  expect(await screen.findByRole('link', { name: '사용자 의견' })).toHaveAttribute('aria-current', 'page')
  fireEvent.click(await screen.findByText('전체 내용 보기'))
  expect(screen.getByRole('region', { name: '의견 7 전체 내용' })).toHaveTextContent('지도 개선 의견 두 번째 줄')
})

it('미인증 사용자는 관리자 로그인으로 이동하며 의견 조회를 호출하지 않는다', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }))
  vi.stubGlobal('fetch', fetchMock)
  render(<MemoryRouter initialEntries={['/admin/feedback']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  expect(fetchMock.mock.calls.every(([url]) => String(url).endsWith('/api/admin/auth/me'))).toBe(true)
})
