import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { NotificationManagementPage } from './NotificationSubscriptions'
import { loadNotificationSubscriptions, type NotificationPage } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), loadNotificationSubscriptions: vi.fn() }))
const page: NotificationPage = { items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0,
  summary: { activeMembers: 0, activeSubscriptions: 0, asOf: '2026-10-13T00:00:00Z' } }
beforeEach(() => { vi.mocked(loadNotificationSubscriptions).mockReset().mockResolvedValue(page) })
function Location() { const value = useLocation(); return <output aria-label="현재 경로">{value.pathname}{value.search}</output> }
function mount(path = '/admin/notification-subscriptions') {
  return render(<MemoryRouter initialEntries={[path]}><Location /><NotificationManagementPage /></MemoryRouter>)
}

it('일반 신청 검색은 점검 조건을 상세 검색에 두고 적용된 점검 조건은 펼쳐 보여 준다', async () => {
  const view = mount()
  await screen.findByText('표시할 알림 신청이 없습니다.')
  expect(screen.getByLabelText('확인 항목').closest('details')).not.toHaveAttribute('open')
  view.unmount()
  mount('/admin/notification-subscriptions?review=TARGET_UNAVAILABLE')
  await screen.findByText('표시할 알림 신청이 없습니다.')
  expect(screen.getByLabelText('확인 항목').closest('details')).toHaveAttribute('open')
  expect(screen.getByRole('list', { name: '적용된 검색 조건' })).toHaveTextContent('확인: 대상 확인 불가')
})

it('빈 신청은 0건으로 표시하고 실패는 빈 목록으로 위장하지 않는다', async () => {
  vi.mocked(loadNotificationSubscriptions).mockRejectedValueOnce(new Error('목록 연결 실패')).mockResolvedValue(page)
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent('목록 연결 실패')
  expect(screen.queryByText('표시할 알림 신청이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('표시할 알림 신청이 없습니다.')).toBeVisible()
  expect(screen.getByText('0명')).toBeVisible()
  expect(screen.queryByRole('navigation', { name: '알림 신청 목록 페이지' })).not.toBeInTheDocument()
})

it('실패한 조건으로 돌아오면 새 조회의 로딩과 성공 결과를 표시하고 이전 오류를 지운다', async () => {
  let resolve: (value: NotificationPage) => void = () => { throw new Error('not initialized') }
  const pending = new Promise<NotificationPage>(yes => { resolve = yes })
  vi.mocked(loadNotificationSubscriptions).mockRejectedValueOnce(new Error('활성 신청 조회 실패'))
    .mockResolvedValueOnce(page).mockReturnValueOnce(pending)
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent('활성 신청 조회 실패')
  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'CANCELLED' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('표시할 알림 신청이 없습니다.')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()

  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'ACTIVE' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(loadNotificationSubscriptions).toHaveBeenCalledTimes(3))
  expect(screen.getByText('알림 신청 목록을 불러오는 중…')).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  await act(() => resolve(page))
  expect(await screen.findByText('표시할 알림 신청이 없습니다.')).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('잘못된 URL 필터는 요청하지 않고 초기화할 수 있다', async () => {
  mount('/admin/notification-subscriptions?status=INVALID&page=-1')
  expect(screen.getByRole('alert')).toHaveTextContent('검색 조건을 확인해 주세요.')
  expect(loadNotificationSubscriptions).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  await screen.findByText('표시할 알림 신청이 없습니다.')
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('/admin/notification-subscriptions')
})

it('회원 검색어를 URL에 남기지 않고 초기화한다', async () => {
  mount()
  await screen.findByText('표시할 알림 신청이 없습니다.')
  fireEvent.change(screen.getByLabelText('로그인 이메일·회원 ID'), { target: { value: 'member@example.test' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(vi.mocked(loadNotificationSubscriptions).mock.lastCall?.[0].get('keyword')).toBe('member@example.test'))
  expect(screen.getByLabelText('현재 경로')).not.toHaveTextContent('member')
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  await waitFor(() => expect(vi.mocked(loadNotificationSubscriptions).mock.lastCall?.[0].get('keyword')).toBeNull())
})

it('검색하지 않은 입력도 초기화하면 기본 검색 조건으로 돌아간다', async () => {
  mount()
  await screen.findByText('표시할 알림 신청이 없습니다.')
  fireEvent.change(screen.getByLabelText('로그인 이메일·회원 ID'), { target: { value: 'draft@example.test' } })
  fireEvent.change(screen.getByLabelText('대상 종류'), { target: { value: 'COMPLEX' } })
  fireEvent.change(screen.getByLabelText('대상 ID'), { target: { value: '22' } })
  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'CANCELLED' } })
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByLabelText('로그인 이메일·회원 ID')).toHaveValue('')
  expect(screen.getByLabelText('대상 종류')).toHaveValue('')
  expect(screen.getByLabelText('대상 ID')).toHaveValue('')
  expect(screen.getByLabelText('신청 상태')).toHaveValue('ACTIVE')
})

it('선행 0 대상 ID를 표시하고 실제 단지 상세 경로로 연결한다', async () => {
  vi.mocked(loadNotificationSubscriptions).mockResolvedValue({ ...page, totalElements: 1, totalPages: 1,
    items: [{ id: '1', userId: '7', email: null, provider: 'GOOGLE', targetType: 'COMPLEX', targetId: '01',
      targetName: '신청 단지', status: 'ACTIVE', requestedAt: null, expiresAt: '2027-10-13T00:00:00Z' }],
  })
  mount('/admin/notification-subscriptions?targetId=01')
  expect(await screen.findByRole('link', { name: '신청 단지' })).toHaveAttribute('href', '/admin/complexes/1')
  expect(screen.getByRole('table')).toHaveTextContent('단지 · 01')
})

it('조건 변경은 이전 요청을 취소하고 늦은 결과가 최신 목록을 덮지 못한다', async () => {
  let resolve: (value: NotificationPage) => void = () => { throw new Error('not initialized') }
  const pending = new Promise<NotificationPage>(yes => { resolve = yes })
  vi.mocked(loadNotificationSubscriptions).mockReturnValueOnce(pending).mockResolvedValue(page)
  mount()
  const signal = vi.mocked(loadNotificationSubscriptions).mock.lastCall?.[1]
  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'CANCELLED' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(signal?.aborted).toBe(true)
  await screen.findByText('표시할 알림 신청이 없습니다.')
  await act(() => resolve({ ...page, summary: { ...page.summary, activeMembers: 99 } }))
  expect(screen.queryByText('99명')).not.toBeInTheDocument()
})

it('신청자 정보를 한 칸으로 묶고 이메일이 없어도 회원 상세를 연다', async () => {
  vi.mocked(loadNotificationSubscriptions).mockResolvedValue({ ...page, totalElements: 1, totalPages: 1,
    items: [{ id: '1', userId: '7', email: null, provider: 'GOOGLE', targetType: 'REGION', targetId: '11',
      targetName: '서울특별시 전체', status: 'ACTIVE', requestedAt: null, expiresAt: '2027-10-13T00:00:00Z' }],
  })
  mount()
  const table = await screen.findByRole('table', { name: '알림 신청 목록' })
  const applicant = within(table).getByRole('cell', { name: /회원 7 상세 보기.*Google/ })
  expect(applicant).toHaveTextContent('Google')
  expect(applicant).toHaveTextContent('이메일 미제공')
  expect(within(applicant).getByRole('link', { name: '회원 7 상세 보기' })).toHaveAttribute('href',
    '/admin/users/7?returnTo=%2Fadmin%2Fnotification-subscriptions')
  expect(within(table).getByText('미확인')).toHaveAttribute('title', '신청 시각 기록 없음')
})

it('입력 중인 조건과 실제 적용된 조건을 구분하고 빈 결과에서 초기화한다', async () => {
  mount()
  await screen.findByText('표시할 알림 신청이 없습니다.')
  expect(screen.queryByRole('list', { name: '적용된 검색 조건' })).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'CANCELLED' } })
  expect(screen.queryByRole('list', { name: '적용된 검색 조건' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(screen.getByRole('list', { name: '적용된 검색 조건' })).toHaveTextContent('상태: 취소'))
  expect(await screen.findByRole('button', { name: '검색 조건 초기화' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '검색 조건 초기화' }))
  await waitFor(() => expect(vi.mocked(loadNotificationSubscriptions).mock.lastCall?.[0].get('status')).toBe('ACTIVE'))
  expect(screen.getByLabelText('신청 상태')).toHaveValue('ACTIVE')
})

it('대상 ID 필터가 있는 주소는 상세 검색을 펼쳐 숨겨진 조건을 보여 준다', async () => {
  mount('/admin/notification-subscriptions?targetType=REGION&targetId=11')
  await screen.findByText('표시할 알림 신청이 없습니다.')
  const input = screen.getByLabelText('대상 ID')
  expect(input.closest('details')).toHaveAttribute('open')
  expect(input).toHaveAccessibleDescription('지역 코드 또는 단지·공고 ID')
  expect(screen.getByRole('list', { name: '적용된 검색 조건' })).toHaveTextContent('대상 ID: 11')
  fireEvent.click(screen.getByRole('button', { name: '검색 조건 초기화' }))
  await waitFor(() => expect(screen.getByLabelText('대상 ID').closest('details')).not.toHaveAttribute('open'))
})

it('한 페이지는 이동 버튼을 생략하고 여러 페이지일 때만 이동할 수 있다', async () => {
  const item = { id: '1', userId: '7', email: null, provider: 'GOOGLE' as const, targetType: 'REGION' as const,
    targetId: '11', targetName: '서울특별시 전체', status: 'ACTIVE' as const, requestedAt: null,
    expiresAt: '2027-10-13T00:00:00Z' }
  vi.mocked(loadNotificationSubscriptions).mockResolvedValueOnce({ ...page, items: [item], totalElements: 1, totalPages: 1 })
    .mockImplementation(async query => ({ ...page, items: [item], page: Number(query.get('page')),
      totalElements: 21, totalPages: 2, hasNext: query.get('page') === '0' }))
  mount()
  await screen.findByRole('table')
  expect(screen.queryByRole('navigation', { name: '알림 신청 목록 페이지' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '새로고침' }))
  const navigation = await screen.findByRole('navigation', { name: '알림 신청 목록 페이지' })
  fireEvent.click(within(navigation).getByRole('button', { name: '다음' }))
  await waitFor(() => expect(vi.mocked(loadNotificationSubscriptions).mock.lastCall?.[0].get('page')).toBe('1'))
  await waitFor(() => expect(within(navigation).getByRole('button', { name: '2페이지' })).toHaveAttribute('aria-current', 'page'))
})
