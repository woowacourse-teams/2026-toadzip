import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../../App'

afterEach(() => vi.unstubAllGlobals())
const item = { id: '1', userId: '7', email: 'member@example.test', provider: 'KAKAO', targetType: 'REGION',
  targetId: '11', targetName: '서울특별시 전체', status: 'ACTIVE', requestedAt: null, expiresAt: '2027-10-13T00:00:00Z' }
const result = { items: [item], page: 0, hasNext: false, totalElements: 1, totalPages: 1,
  summary: { activeMembers: 1, activeSubscriptions: 3, asOf: '2026-10-13T00:00:00Z' } }
function setup() {
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/api/admin/auth/me')) return Response.json({ loginIdentifier: 'admin', role: 'ADMIN' })
    if (url.includes('/notification-subscriptions')) return Response.json({ data: result })
    if (url.endsWith('/api/admin/users/7')) return Response.json({ data:
      { id: 7, email: item.email, provider: 'KAKAO', createdAt: '2026-10-04T09:12:34' } })
    return Response.json([])
  })
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

it('관리자 메뉴에서 회원 신청을 보고 회원 상세의 신청까지 확인한다', async () => {
  setup()
  render(<MemoryRouter initialEntries={['/admin/notification-subscriptions']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '알림 신청 관리', level: 1 })).toBeVisible()
  const table = await screen.findByRole('table', { name: '알림 신청 목록' })
  expect(table).toHaveTextContent(item.email)
  expect(table).toHaveTextContent('서울특별시 전체')
  expect(table).toHaveTextContent('미확인')
  expect(screen.getByText('전체 활성 신청 회원')).toBeVisible()
  expect(screen.getByText('전체 활성 신청 건수')).toBeVisible()
  fireEvent.click(screen.getByRole('link', { name: '회원 7 상세 보기 · member@example.test' }))
  expect(await screen.findByRole('heading', { name: '회원 7', level: 1 })).toBeVisible()
  expect(await screen.findByRole('table', { name: '회원 알림 신청 목록' })).toHaveTextContent(item.targetName)
  expect(screen.getByRole('link', { name: '목록으로' })).toHaveAttribute('href', '/admin/notification-subscriptions')
})

it('회원 검색어를 주소에 넣지 않고 상태·대상으로 검색한다', async () => {
  const fetcher = setup()
  render(<MemoryRouter initialEntries={['/admin/notification-subscriptions']}><App /></MemoryRouter>)
  await screen.findByRole('table', { name: '알림 신청 목록' })
  fireEvent.change(screen.getByLabelText('로그인 이메일·회원 ID'), { target: { value: ' member@ ' } })
  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'CANCELLED' } })
  fireEvent.change(screen.getByLabelText('대상 종류'), { target: { value: 'REGION' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) =>
    String(url).includes('keyword=member%40') && String(url).includes('status=CANCELLED')
      && String(url).includes('targetType=REGION'))).toBe(true))
  expect(screen.getByRole('link', { name: '회원 7 상세 보기 · member@example.test' }).getAttribute('href')).not.toContain('member')
})

it('미인증 사용자는 신청 API를 호출하지 않고 관리자 로그인으로 이동한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
  vi.stubGlobal('fetch', fetcher)
  render(<MemoryRouter initialEntries={['/admin/notification-subscriptions']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  expect(fetcher.mock.calls.every(([url]) => String(url).endsWith('/api/admin/auth/me'))).toBe(true)
})
