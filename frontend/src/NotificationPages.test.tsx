import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { Link, MemoryRouter, useLocation } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'
import { myPageReturnPath } from './user/mypage/mypageNavigation'

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()
  return { ...original, notificationInterestRepository: original.createNotificationInterestRepository((...args) => globalThis.fetch(...args)) }
})
vi.mock('./public-housing/DefaultPublicHousingExplorer', () => ({ DefaultPublicHousingExplorer: () => <div>지도</div> }))
afterEach(() => vi.unstubAllGlobals())

function session(member = true) {
  let active = true
  let settingsRevision = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/bookmarks/me')) return Response.json({ items: [] })
    if (url.endsWith('/csrf')) return new Response(JSON.stringify({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
    if (!member) return new Response(null, { status: 401 })
    if (url.includes('/api/v1/notifications/unread-count')) return Response.json({ unreadCount: 0 })
    if (url.includes('/api/v1/notifications?')) return Response.json({ items: [], nextCursor: null, readThrough: '0', totalCount: 0 })
    if (url.endsWith('/api/auth/me')) return new Response(JSON.stringify({ id: 7 }))
    if (options?.method === 'POST') { active = false; return settingResult(options, ++settingsRevision) }
    return new Response(JSON.stringify({ userId: '7', settingsRevision, targets: active ? [
      { ...targetNotice, targetType: 'COMPLEX', targetId: '1', targetName: '서울 행복주택' },
      { ...targetNotice, targetType: 'ANNOUNCEMENT', targetId: '2', targetName: '가을 모집 공고' },
      { ...targetNotice, targetType: 'REGION', targetId: '11' },
    ] : [] }))
  }))
}

it('알림 관리 주소에서 종류별 설정을 보고 해제한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '알림 관리' })).toBeVisible()
  expect(await screen.findByText('서울 행복주택')).toBeVisible()
  expect(screen.queryByText(/알림을 받고 있어요/)).not.toBeInTheDocument()
  expect(screen.queryByText('가을 모집 공고')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  expect(screen.getByText('가을 모집 공고')).toBeVisible()
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  expect(screen.getByText('서울특별시 전체')).toBeVisible()
  fireEvent.click(screen.getByRole('tab', { name: '단지 1' }))
  fireEvent.click(screen.getByRole('button', { name: '서울 행복주택 알림 해제' }))
  await waitFor(() => expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument())
})

it('보관함 주소에서 받은 알림 목록을 확인한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/notifications']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '알림 보관함' })).toBeVisible()
  expect(await screen.findByText('아직 받은 알림이 없어요.')).toBeVisible()
})

it('비회원이 관리 주소로 직접 접속해도 설정을 노출하지 않는다', async () => {
  session(false)
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  expect(await screen.findByText('로그인하면 저장한 주택과 알림을 한곳에서 관리할 수 있어요.')).toBeVisible()
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
})

it('전체 해제는 단지·공고·지역 설정을 모두 비우고 빈 목록에서는 비활성화된다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  fireEvent.click(screen.getByRole('button', { name: '모든 알림 해제' }))
  await waitFor(() => expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument())
  expect(screen.queryByText('가을 모집 공고')).not.toBeInTheDocument()
  expect(screen.queryByText('서울특별시 전체')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '모든 알림 해제' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('알림 설정을 모두 해제했어요.')
})

it('전체 해제 중 실패한 설정은 남기고 같은 이벤트로 재시도한다', async () => {
  let shouldFail = true
  let settingsRevision = 0
  const writes: Array<{ eventId: string; targetId: string }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/api/auth/me')) return Response.json({ id: 7 })
    if (url.endsWith('/bookmarks/me')) return Response.json({ items: [] })
    if (url.endsWith('/csrf')) return Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
    if (options?.method === 'POST') {
      const event = JSON.parse(String(options.body)) as { eventId: string; targetId: string }
      writes.push(event)
      if (event.targetId === '2' && shouldFail) return new Response(null, { status: 500 })
      return settingResult(options, ++settingsRevision)
    }
    return Response.json({ userId: '7', settingsRevision: 0, targets: [
      { ...targetNotice, targetType: 'COMPLEX', targetId: '1', targetName: '서울 행복주택' },
      { ...targetNotice, targetType: 'ANNOUNCEMENT', targetId: '2', targetName: '가을 모집 공고' },
      { ...targetNotice, targetType: 'REGION', targetId: '11' },
    ] })
  }))
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  fireEvent.click(screen.getByRole('button', { name: '모든 알림 해제' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('1개 설정을 해제하지 못했어요.')
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  expect(screen.getByText('서울특별시 전체')).toBeVisible()
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  expect(screen.getByText('가을 모집 공고')).toBeVisible()
  shouldFail = false
  fireEvent.click(screen.getByRole('button', { name: '모든 알림 해제' }))
  await waitFor(() => expect(screen.queryByText('가을 모집 공고')).not.toBeInTheDocument())
  expect(writes.filter((event) => event.targetId === '2').map((event) => event.eventId))
    .toEqual([writes[1]?.eventId, writes[1]?.eventId])
  expect(writes).toHaveLength(4)
})

it('전체 해제 중에는 중복 클릭과 개별 해제를 막는다', async () => {
  session()
  const fetcher = globalThis.fetch
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  let writes = 0
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => {
    if (options?.method === 'POST') { writes++; await pending }
    return fetcher(url, options)
  }))
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  const clearAll = screen.getByRole('button', { name: '모든 알림 해제' })
  fireEvent.click(clearAll)
  fireEvent.click(clearAll)
  expect(clearAll).toBeDisabled()
  expect(clearAll).toHaveTextContent('모든 알림 해제')
  expect(screen.queryByRole('button', { name: '서울 행복주택 알림 해제' })).not.toBeInTheDocument()
  await waitFor(() => expect(writes).toBe(1))
  release()
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('알림 설정을 모두 해제했어요.'))
  expect(writes).toBe(3)
})


it('지도에서 보관함을 모달로 열고 닫으면 원래 버튼으로 포커스가 돌아온다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  const trigger = await screen.findByRole('button', { name: '알림 보관함' })
  trigger.focus()
  fireEvent.click(trigger)
  const dialog = await screen.findByRole('dialog', { name: '알림 보관함' })
  expect(await within(dialog).findByText('아직 받은 알림이 없어요.')).toBeVisible()
  expect(screen.getByText('지도')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '알림 보관함 닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(trigger).toHaveFocus()
})

it('모바일 마이페이지에서 알림 관리와 의견 보내기 및 계정에 접근한다', async () => {
  session()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('link', { name: '마이페이지' }))
  expect(await screen.findByRole('heading', { name: '마이페이지' })).toBeVisible()
  expect(screen.getByRole('link', { name: '제보/의견 보내기' })).toHaveAttribute('href', '/feedback')
  fireEvent.click(screen.getByRole('link', { name: '알림 관리' }))
  expect(await screen.findByText('서울 행복주택')).toBeVisible()
  expect(screen.getByRole('link', { name: '알림 보관함' })).toHaveAttribute('href', '/notifications')
  fireEvent.click(screen.getByRole('link', { name: '계정' }))
  expect(await screen.findByRole('button', { name: '로그아웃' })).toBeVisible()
})

it('지도 보관함은 받은 알림만 표시하고 설정은 마이페이지에서 관리한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '알림 보관함' }))
  const dialog = screen.getByRole('dialog', { name: '알림 보관함' })
  expect(await within(dialog).findByText('아직 받은 알림이 없어요.')).toBeVisible()
  expect(within(dialog).queryByRole('tab', { name: '알림 설정' })).not.toBeInTheDocument()
  expect(within(dialog).queryByText('서울 행복주택')).not.toBeInTheDocument()
  fireEvent.click(within(dialog).getByRole('button', { name: '알림 보관함 닫기' }))
  fireEvent.click(screen.getByRole('link', { name: '마이페이지' }))
  fireEvent.click(await screen.findByRole('link', { name: '알림 관리' }))
  expect(await screen.findByRole('table', { name: '단지 알림 신청 내역' })).toHaveTextContent('서울 행복주택')
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  expect(screen.getByRole('table', { name: '공고 알림 신청 내역' })).toHaveTextContent('가을 모집 공고')
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  expect(screen.getByRole('table', { name: '지역 알림 신청 내역' })).toHaveTextContent('서울특별시 전체')
  fireEvent.click(screen.getByRole('link', { name: '알림 보관함' }))
  fireEvent.click(await screen.findByRole('link', { name: '마이페이지로 돌아가기' }))
  expect(await screen.findByRole('heading', { name: '알림 관리' })).toBeVisible()
  expect(await screen.findByRole('tab', { name: '지역 1' })).toHaveAttribute('aria-selected', 'true')
})

it('이전 정보 메뉴 주소는 북마크와 공통 푸터로 연결한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/info']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '내 북마크' })).toBeVisible()
  expect(screen.queryByRole('link', { name: '정보 및 설정' })).not.toBeInTheDocument()
  expect(screen.getByRole('contentinfo')).toHaveTextContent('개인정보처리방침')
})

it('마이페이지에서 의견 화면으로 이동하면 원래 탭으로 복귀한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/account']}><App /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('link', { name: '제보/의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견 보내기' })).toBeVisible()
  fireEvent.click(screen.getByRole('link', { name: '마이페이지로 돌아가기' }))
  expect(await screen.findByRole('heading', { name: '계정' })).toBeVisible()
})


vi.mock('./privacy/usePrivacy', async (importOriginal) => {
  const original = await importOriginal<typeof import('./privacy/usePrivacy')>()
  const notices = [{ key: 'PRIVACY_POLICY', version: 'privacy-2026-10-09-v1' }, { key: 'NOTIFICATION_NOTICE', version: 'notification-2026-10-09-v1' }]
  return { ...original, usePrivacyNotices: () => ({ notices, error: false, retry: vi.fn() }) }
})

const targetNotice = { noticeVersion: 'notification-2026-10-09-v1', requestedAt: '2026-10-09T00:00:00Z', expiresAt: '2027-10-09T00:00:00Z' }
function settingResult(options: RequestInit, settingsRevision: number) {
  const event = JSON.parse(String(options.body))
  return Response.json({ eventId: event.eventId, targetId: event.targetId, targetType: event.targetType, outcome: 'CANCELLED', occurredAt: '2026-10-09T00:00:00Z', settingsRevision, currentTarget: { active: false, noticeVersion: null, requestedAt: null, expiresAt: null } })
}


it('공유 레이아웃 안에서 화면을 이동하면 미읽음 개수를 즉시 다시 조회한다', async () => {
  session()
  const countRequests = () => vi.mocked(fetch).mock.calls.filter(([url]) => String(url).includes('/notifications/unread-count')).length
  render(<MemoryRouter initialEntries={['/notifications']}><Link to="/mypage/notifications">설정 화면으로 이동</Link><App /></MemoryRouter>)
  await waitFor(() => expect(countRequests()).toBe(1))
  fireEvent.click(screen.getByRole('link', { name: '설정 화면으로 이동' }))
  expect(await screen.findByRole('heading', { name: '알림 관리' })).toBeVisible()
  await waitFor(() => expect(countRequests()).toBe(2))
})

it('알림 분류는 키보드로 이동하고 비어 있는 분류도 선택할 수 있다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  const complex = await screen.findByRole('tab', { name: '단지 1' })
  complex.focus()
  fireEvent.keyDown(complex, { key: 'ArrowRight' })
  expect(screen.getByRole('tab', { name: '공고 1' })).toHaveFocus()
  expect(screen.getByRole('tab', { name: '공고 1' })).toHaveAttribute('aria-selected', 'true')
  fireEvent.keyDown(document.activeElement!, { key: 'End' })
  expect(screen.getByRole('tab', { name: '지역 1' })).toHaveFocus()
  fireEvent.keyDown(document.activeElement!, { key: 'Home' })
  fireEvent.click(screen.getByRole('button', { name: '서울 행복주택 알림 해제' }))
  expect(await screen.findByText('등록한 단지 알림이 없어요.')).toBeVisible()
  expect(screen.getByRole('tab', { name: '단지 0' })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('tab', { name: '단지 0' })).toHaveFocus()
  expect(screen.queryByRole('heading', { name: /관심 알림/ })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '모든 알림 해제' })).toBeEnabled()
})

function NotificationLocation() {
  const location = useLocation()
  return <output aria-label="현재 위치" data-mypage-return={myPageReturnPath(location.state)}>{location.pathname}{location.search}</output>
}

it.each([
  ['/mypage/notifications', '서울 행복주택', '/explore?complexId=1'],
  ['/mypage/notifications?type=ANNOUNCEMENT', '가을 모집 공고', '/explore?announcementId=2'],
])('알림 관리 %s의 이름을 누르면 %s 상세로 이동하며 출발 분류를 유지한다', async (origin, name, detail) => {
  session()
  render(<MemoryRouter initialEntries={[origin]}><App /><NotificationLocation /></MemoryRouter>)
  const link = await screen.findByRole('link', { name })
  expect(link).toHaveAttribute('href', detail)
  fireEvent.click(link)
  expect(screen.getByLabelText('현재 위치')).toHaveTextContent(detail)
  expect(screen.getByLabelText('현재 위치')).toHaveAttribute('data-mypage-return', origin)
  expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(0)
})

it('지역 알림 이름은 단지나 공고 상세 링크로 만들지 않는다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications?type=REGION']}><App /></MemoryRouter>)
  expect(await screen.findByText('서울특별시 전체')).toBeVisible()
  expect(screen.queryByRole('link', { name: '서울특별시 전체' })).not.toBeInTheDocument()
})
