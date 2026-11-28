import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../../App'

afterEach(() => vi.unstubAllGlobals())
const entry = { version: 0, status: 'OPEN', note: '', relatedResource: '', relatedId: null, relatedName: null, actor: null, processedAt: null, id: 7, content: '지도 개선 의견\n두 번째 줄', createdAt: '2026-10-07T01:00:00Z' }

it('관리자 메뉴에서 의견 목록을 열고 의견을 골라 전문과 건수를 확인한다', async () => {
  const fetchMock = vi.fn(async (input: string | URL | Request) => {
    const url = String(input)
    const body = url.endsWith('/api/admin/auth/me') ? { loginIdentifier: 'admin', role: 'ADMIN' }
      : url.includes('/api/admin/feedback/counts') ? { data: { OPEN: 1, IN_PROGRESS: 0, ON_HOLD: 0, DONE: 4, total: 5 } }
        : url.includes('/changes') ? { data: [] }
          : { data: { items: [entry], page: 0, hasNext: false, totalElements: 1, totalPages: 1 } }
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<MemoryRouter initialEntries={['/admin/feedback']}><App /></MemoryRouter>)
  expect(await screen.findByRole('link', { name: '사용자 의견' })).toHaveAttribute('aria-current', 'page')
  expect(await screen.findByRole('button', { name: '전체 5' })).toBeVisible()
  fireEvent.click(await screen.findByRole('button', { name: /지도 개선 의견/ }))
  expect(await screen.findByRole('region', { name: '의견 내용' })).toHaveTextContent('지도 개선 의견 두 번째 줄')
  expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/admin/feedback?') && String(url).includes('status=OPEN'))).toBe(true)
})

it('미인증 사용자는 관리자 로그인으로 이동하며 의견 조회를 호출하지 않는다', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }))
  vi.stubGlobal('fetch', fetchMock)
  render(<MemoryRouter initialEntries={['/admin/feedback']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '로그인' })).toBeVisible()
  expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/admin/')).every(([url]) => String(url).endsWith('/api/admin/auth/me'))).toBe(true)
})
