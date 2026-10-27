import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { Link, MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()
  return { ...original, notificationInterestRepository: original.createNotificationInterestRepository((...args) => globalThis.fetch(...args)) }
})
// 마이페이지는 지도 탐색 화면 안에서 열리므로, 지도 대신 마이페이지 화면만 그리는 대역을 둔다.
vi.mock('./public-housing/DefaultPublicHousingExplorer', async () => {
  const { useLocation } = await import('react-router')
  const { MyPageView } = await import('./user/mypage/MyPageView')
  return { DefaultPublicHousingExplorer: () => useLocation().pathname.startsWith('/mypage')
    ? <MyPageView onOpenComplex={vi.fn()} onOpenAnnouncement={vi.fn()} onBrowseAnnouncements={vi.fn()} onClose={vi.fn()} />
    : <div>지도</div> }
})
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
      { ...targetNotice, targetType: 'ANNOUNCEMENT', targetId: '2', targetName: '가을 모집 공고', status: 'APPLYING', applicationStartDate: '2026-10-20', applicationEndDate: '2026-10-30' },
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
  expect(screen.getByText('접수중')).toBeVisible()
  expect(screen.getByText('접수 10.20 ~ 10.30')).toBeVisible()
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  expect(screen.getByText('서울특별시 전체')).toBeVisible()
  fireEvent.click(screen.getByRole('tab', { name: '단지 1' }))
  fireEvent.click(screen.getByRole('button', { name: '서울 행복주택 알림 취소' }))
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
  expect(await screen.findByText('저장한 단지·공고와 새 공고 알림을 한곳에서 관리할 수 있어요.')).toBeVisible()
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
})

function clearAllAlarms() {
  const all = screen.getByRole<HTMLInputElement>('checkbox', { name: '전체 선택' })
  if (!all.checked) fireEvent.click(all)
  fireEvent.click(screen.getByRole('button', { name: /^알림 끄기 \(\d+\)$/ }))
  const confirm = screen.getByRole('group', { name: '알림 해제 확인' })
  fireEvent.click(within(confirm).getByRole('button', { name: '알림 해제' }))
}

it('전체 해제는 현재 분류만 비우며 다른 분류와 취소한 선택은 보존한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(screen.getByRole('button', { name: /^알림 끄기 \(\d+\)$/ }))
  expect(screen.getByRole('group', { name: '알림 해제 확인' })).toHaveTextContent('단지 알림 1개')
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(screen.getByText('서울 행복주택')).toBeVisible()
  clearAllAlarms()
  await waitFor(() => expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument())
  expect(screen.queryByRole('button', { name: /알림 끄기|^알림 해제$/ })).not.toBeInTheDocument()
  expect(screen.getByRole('tab', { name: '공고 1' })).toBeInTheDocument()
  expect(screen.getByRole('tab', { name: '지역 1' })).toBeInTheDocument()
  await screen.findByText('선택한 알림을 해제했어요.')
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  clearAllAlarms()
  await waitFor(() => expect(within(screen.getByRole('region', { name: '알림 관리' })).getByRole('tab', { name: '공고 0' })).toBeInTheDocument())
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  clearAllAlarms()
  await waitFor(() => expect(screen.getByRole('tab', { name: '지역 0' })).toBeInTheDocument())
  const writes = vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')
    .map(([, options]) => JSON.parse(String(options?.body)))
  expect(writes.map(event => event.targetType)).toEqual(['COMPLEX', 'ANNOUNCEMENT', 'REGION'])
})

it('같은 탭의 일부 해제가 실패하면 미완료 항목만 복원하고 같은 이벤트로 재시도한다', async () => {
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
      { ...targetNotice, targetType: 'COMPLEX', targetId: '2', targetName: '다른 행복주택' },
      { ...targetNotice, targetType: 'ANNOUNCEMENT', targetId: '1', targetName: '가을 모집 공고' },
      { ...targetNotice, targetType: 'REGION', targetId: '11' },
    ] })
  }))
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  clearAllAlarms()
  expect(await screen.findByRole('alert')).toHaveTextContent('1개 설정을 해제하지 못했어요.')
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
  expect(screen.getByText('다른 행복주택')).toBeVisible()
  expect(screen.getByRole('tab', { name: '공고 1' })).toBeInTheDocument()
  expect(screen.getByRole('tab', { name: '지역 1' })).toBeInTheDocument()
  shouldFail = false
  clearAllAlarms()
  await waitFor(() => expect(screen.queryByText('다른 행복주택')).not.toBeInTheDocument())
  expect(writes.filter(event => event.targetId === '2').map(event => event.eventId)).toEqual([writes[1]?.eventId, writes[1]?.eventId])
  expect(writes).toHaveLength(3)
})

it('전체 해제 중에는 다른 탭에서도 중복·개별 해제를 막는다', async () => {
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
  clearAllAlarms()
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: '알림 관리' })).getByRole('tab', { name: '단지 0' })).toHaveFocus()
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  expect(screen.getByRole('checkbox', { name: '전체 선택' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: /알림 끄기|^알림 해제$/ })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '가을 모집 공고 알림 취소' })).toBeDisabled()
  await waitFor(() => expect(writes).toBe(1))
  release()
  await screen.findByText('선택한 알림을 해제했어요.')
  expect(writes).toBe(1)
  expect(screen.getByText('가을 모집 공고')).toBeVisible()
  expect(screen.getByText('접수중')).toBeVisible()
  expect(screen.getByText('접수 10.20 ~ 10.30')).toBeVisible()
})

it('선택 해제는 체크한 항목만 전송하고 다른 종류의 동일 ID는 유지한다', async () => {
  session()
  const fetcher = globalThis.fetch
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => {
    if (String(url).endsWith('/notification-subscriptions/me') && !options?.method) return Response.json({ userId: '7', settingsRevision: 0, targets: [
      { ...targetNotice, targetType: 'COMPLEX', targetId: '1', targetName: '선택한 단지' },
      { ...targetNotice, targetType: 'COMPLEX', targetId: '2', targetName: '남겨둘 단지' },
      { ...targetNotice, targetType: 'ANNOUNCEMENT', targetId: '1', targetName: '동일 ID 공고' },
    ] })
    return fetcher(url, options)
  }))
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('checkbox', { name: '선택한 단지 선택' }))
  fireEvent.click(screen.getByRole('button', { name: '알림 끄기 (1)' }))
  fireEvent.click(within(screen.getByRole('group', { name: '알림 해제 확인' })).getByRole('button', { name: '알림 해제' }))
  await screen.findByText('선택한 알림을 해제했어요.')
  expect(screen.queryByText('선택한 단지')).not.toBeInTheDocument()
  expect(screen.getByText('남겨둘 단지')).toBeVisible()
  expect(screen.getByRole('tab', { name: '공고 1' })).toBeInTheDocument()
  const writes = vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0]?.[1]?.body))).toMatchObject({ targetType: 'COMPLEX', targetId: '1', eventType: 'CANCELLED' })
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

it('모바일 마이페이지 요약에서 알림 관리와 의견 보내기 및 로그아웃에 접근한다', async () => {
  session()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('link', { name: '마이페이지' }))
  expect(await screen.findByRole('button', { name: '로그아웃' })).toBeVisible()
  expect(screen.getByRole('heading', { name: '마이페이지' })).toBeVisible()
  expect(within(screen.getByRole('navigation', { name: '서비스' })).getByRole('link', { name: '의견 보내기' })).toHaveAttribute('href', '/feedback')
  fireEvent.click(await screen.findByRole('link', { name: /알림 관리/ }))
  expect(await screen.findByText('서울 행복주택')).toBeVisible()
  expect(within(screen.getByRole('region', { name: '알림 관리' })).getByRole('link', { name: '마이페이지로 돌아가기' })).toHaveAttribute('href', '/mypage')
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
  fireEvent.click(await screen.findByRole('link', { name: /알림 관리/ }))
  const alarms = await screen.findByRole('tabpanel', { name: '단지 1' })
  expect(await within(alarms).findByText('서울 행복주택')).toBeVisible()
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  expect(screen.getByRole('tabpanel', { name: '공고 1' })).toHaveTextContent('가을 모집 공고')
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  expect(screen.getByRole('tabpanel', { name: '지역 1' })).toHaveTextContent('서울특별시 전체')
})

it('이전 정보·계정 메뉴 주소는 마이페이지 요약과 개인정보 링크로 연결한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/info']}><App /></MemoryRouter>)
  await screen.findByRole('button', { name: '로그아웃' })
  expect(screen.getByRole('heading', { name: '마이페이지' })).toBeVisible()
  expect(screen.queryByRole('link', { name: '정보 및 설정' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy')
})

it('마이페이지에서 의견 화면으로 이동하면 마이페이지로 복귀한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/account']}><App /></MemoryRouter>)
  fireEvent.click(within(await screen.findByRole('navigation', { name: '서비스' })).getByRole('link', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견 보내기' })).toBeVisible()
  fireEvent.click(screen.getByRole('link', { name: '마이페이지로 돌아가기' }))
  await screen.findByRole('button', { name: '로그아웃' })
  expect(screen.getByRole('heading', { name: '마이페이지' })).toBeVisible()
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
  expect(screen.getByRole('tab', { name: '기기 관리' })).toHaveFocus()
  fireEvent.keyDown(document.activeElement!, { key: 'Home' })
  fireEvent.click(screen.getByRole('button', { name: '서울 행복주택 알림 취소' }))
  expect(await screen.findByText('등록한 단지 알림이 없어요.')).toBeVisible()
  const alarms = within(screen.getByRole('region', { name: '알림 관리' }))
  expect(alarms.getByRole('tab', { name: '단지 0' })).toHaveAttribute('aria-selected', 'true')
  expect(alarms.getByRole('tab', { name: '단지 0' })).toHaveFocus()
  expect(screen.queryByRole('heading', { name: /관심 알림/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /알림 끄기|^알림 해제$/ })).not.toBeInTheDocument()
})

it('지역 알림 이름은 단지나 공고 상세 링크로 만들지 않는다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications?type=REGION']}><App /></MemoryRouter>)
  expect(await screen.findByText('서울특별시 전체')).toBeVisible()
  expect(screen.queryByRole('link', { name: '서울특별시 전체' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '서울특별시 전체' })).not.toBeInTheDocument()
})
