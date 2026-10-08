import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { NotificationInterestRepository, NotificationSubscriptionStatus } from './public-housing/interest/notificationInterestRepository'

const interest = vi.hoisted(() => ({ record: vi.fn(), loadStatus: vi.fn() }))

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()),
  notificationInterestRepository: interest,
}))

vi.mock('./public-housing/DefaultPublicHousingExplorer', async () => {
  const { NotificationInterestButton } = await import('./public-housing/interest/NotificationInterest')
  return {
    DefaultPublicHousingExplorer: () => <>
      <input aria-label="현재 검색어" defaultValue="서울" />
      <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
    </>,
  }
})

const memberStatus = { emailConfirmed: true, targets: [{ targetType: 'COMPLEX', targetId: '1' }] } satisfies NotificationSubscriptionStatus
const guestStatus = { guest: true, emailConfirmed: false, targets: [] } satisfies NotificationSubscriptionStatus

function renderHome(logoutFails = false) {
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input), 'http://localhost').pathname
    if (path === '/api/auth/me') return Response.json({ id: 7, email: 'member@example.com' })
    if (path === '/api/auth/csrf') return Response.json({ token: 'csrf', headerName: 'X-CSRF' })
    if (path === '/api/auth/logout') return new Response(null, { status: logoutFails ? 500 : 204 })
    throw new Error(`Unexpected request: ${path}`)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<MemoryRouter><App /></MemoryRouter>)
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  interest.record.mockReset().mockResolvedValue(undefined)
  interest.loadStatus.mockReset().mockResolvedValueOnce(memberStatus).mockResolvedValue(guestStatus)
})

afterEach(() => { vi.unstubAllGlobals() })

describe('홈의 사용자 세션과 알림 상태', () => {
  it('로그아웃하면 탐색 화면은 유지하면서 이전 회원 조회를 버리고 비로그인 신청 상태를 다시 읽는다', async () => {
    const previousRead = deferred<NotificationSubscriptionStatus>()
    const guestRead = deferred<NotificationSubscriptionStatus>()
    interest.loadStatus.mockReset().mockResolvedValueOnce(memberStatus)
      .mockReturnValueOnce(previousRead.promise).mockReturnValueOnce(guestRead.promise)
    renderHome()
    await screen.findByRole('button', { name: '서울 단지 알림 취소' })
    fireEvent.change(screen.getByRole('textbox', { name: '현재 검색어' }), { target: { value: '마포' } })
    fireEvent.focus(window)
    fireEvent.click(await screen.findByText('마이페이지'))
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
    await screen.findByRole('button', { name: '로그인' })
    expect(interest.loadStatus).toHaveBeenCalledTimes(3)
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeDisabled()
    await act(async () => guestRead.resolve(guestStatus))
    await act(async () => previousRead.resolve(memberStatus))
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled()
    expect(screen.getByRole('textbox', { name: '현재 검색어' })).toHaveValue('마포')
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    expect(await screen.findByRole('dialog', { name: '이메일 알림 신청' })).toBeVisible()
  })

  it('로그아웃 실패는 기존 회원 신청을 지우거나 다시 읽지 않는다', async () => {
    renderHome(true)
    await screen.findByRole('button', { name: '서울 단지 알림 취소' })
    fireEvent.click(await screen.findByText('마이페이지'))
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
    await screen.findByText('로그아웃 실패')
    expect(screen.getByRole('button', { name: '서울 단지 알림 취소' })).toBeEnabled()
    expect(interest.loadStatus).toHaveBeenCalledOnce()
    expect(screen.queryByRole('button', { name: '로그인' })).not.toBeInTheDocument()
  })

  it('로그아웃 전에 보낸 신청이 늦게 성공해도 비로그인 신청이나 입력창으로 복구하지 않는다', async () => {
    const pending = deferred<Awaited<ReturnType<NotificationInterestRepository['record']>>>()
    interest.loadStatus.mockReset().mockResolvedValueOnce({ emailConfirmed: true, targets: [] }).mockResolvedValue(guestStatus)
    interest.record.mockReturnValueOnce(pending.promise)
    renderHome()
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '서울 단지 알림 받기' }))
    fireEvent.click(await screen.findByText('마이페이지'))
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
    await screen.findByRole('button', { name: '로그인' })
    await waitFor(() => expect(interest.loadStatus).toHaveBeenCalledTimes(2))
    await act(async () => pending.resolve())
    expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByText('서울 단지 알림 신청을 받았어요.')).not.toBeInTheDocument()
  })
})

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}
