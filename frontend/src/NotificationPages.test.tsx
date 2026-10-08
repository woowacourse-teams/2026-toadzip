import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()
  return { ...original, notificationInterestRepository: original.createNotificationInterestRepository((...args) => globalThis.fetch(...args)) }
})
vi.mock('./public-housing/DefaultPublicHousingExplorer', () => ({ DefaultPublicHousingExplorer: () => <div>지도</div> }))
afterEach(() => vi.unstubAllGlobals())

function session(member = true) {
  let active = true
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/csrf')) return new Response(JSON.stringify({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
    if (!member) return new Response(null, { status: 401 })
    if (url.endsWith('/api/auth/me')) return new Response(JSON.stringify({ id: 7 }))
    if (options?.method === 'POST') { active = false; return new Response(null, { status: 204 }) }
    return new Response(JSON.stringify({ emailConfirmed: false, targets: active ? [
      { targetType: 'COMPLEX', targetId: '1', targetName: '서울 행복주택' },
      { targetType: 'ANNOUNCEMENT', targetId: '2', targetName: '가을 모집 공고' },
      { targetType: 'REGION', targetId: '11' },
    ] : [] }))
  }))
}

it('알림 관리 주소에서 종류별 설정을 보고 해제한다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '알림 관리' })).toBeVisible()
  expect(await screen.findByText('서울 행복주택')).toBeVisible()
  expect(screen.getByText('가을 모집 공고')).toBeVisible()
  expect(screen.getByText('서울특별시 전체')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '서울 행복주택 알림 해제' }))
  await waitFor(() => expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument())
})

it('보관함은 아직 전달 기능이 준비 중임을 알린다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/notifications']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '알림 보관함' })).toBeVisible()
  expect(await screen.findByText('알림 기능을 준비하고 있어요')).toBeVisible()
  expect(screen.getByRole('link', { name: '알림 관리' })).toHaveAttribute('href', '/mypage/notifications')
})

it('비회원이 관리 주소로 직접 접속해도 설정을 노출하지 않는다', async () => {
  session(false)
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  expect(await screen.findByText('로그인한 사용자만 이용할 수 있어요.')).toBeVisible()
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
})

it('전체 해제는 단지·공고·지역 설정을 모두 비우고 빈 목록에서는 비활성화된다', async () => {
  session()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  fireEvent.click(screen.getByRole('button', { name: '전체 해제' }))
  await waitFor(() => expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument())
  expect(screen.queryByText('가을 모집 공고')).not.toBeInTheDocument()
  expect(screen.queryByText('서울특별시 전체')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '전체 해제' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('알림 설정을 모두 해제했어요.')
})

it('전체 해제 중 실패한 설정은 남기고 같은 이벤트로 재시도한다', async () => {
  let shouldFail = true
  const writes: Array<{ eventId: string; targetId: string }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/csrf')) return Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
    if (options?.method === 'POST') {
      const event = JSON.parse(String(options.body)) as { eventId: string; targetId: string }
      writes.push(event)
      if (event.targetId === '2' && shouldFail) return new Response(null, { status: 500 })
      return new Response(null, { status: 204 })
    }
    return Response.json({ emailConfirmed: false, targets: [
      { targetType: 'COMPLEX', targetId: '1', targetName: '서울 행복주택' },
      { targetType: 'ANNOUNCEMENT', targetId: '2', targetName: '가을 모집 공고' },
      { targetType: 'REGION', targetId: '11' },
    ] })
  }))
  render(<MemoryRouter initialEntries={['/mypage/notifications']}><App /></MemoryRouter>)
  await screen.findByText('서울 행복주택')
  fireEvent.click(screen.getByRole('button', { name: '전체 해제' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('1개 설정을 해제하지 못했어요.')
  expect(screen.queryByText('서울 행복주택')).not.toBeInTheDocument()
  expect(screen.queryByText('서울특별시 전체')).not.toBeInTheDocument()
  expect(screen.getByText('가을 모집 공고')).toBeVisible()
  shouldFail = false
  fireEvent.click(screen.getByRole('button', { name: '전체 해제' }))
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
  const clearAll = screen.getByRole('button', { name: '전체 해제' })
  fireEvent.click(clearAll)
  fireEvent.click(clearAll)
  expect(clearAll).toBeDisabled()
  expect(clearAll).toHaveTextContent('전체 해제 중…')
  expect(screen.getByRole('button', { name: '서울 행복주택 알림 해제' })).toBeDisabled()
  await waitFor(() => expect(writes).toBe(1))
  release()
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('알림 설정을 모두 해제했어요.'))
  expect(writes).toBe(3)
})
