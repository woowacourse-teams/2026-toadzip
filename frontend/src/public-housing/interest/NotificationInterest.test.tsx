import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'
import type { NotificationInterestRepository, NotificationSubscriptionStatus } from './notificationInterestRepository'

beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
const member: NotificationSubscriptionStatus = { emailConfirmed: false, targets: [] }
const guest: NotificationSubscriptionStatus = { guest: true, emailConfirmed: false, targets: [] }
function repository(status = member) {
  return { record: vi.fn<NotificationInterestRepository['record']>().mockResolvedValue(undefined), loadStatus: vi.fn().mockResolvedValue(status) }
}
function example(repo: NotificationInterestRepository) {
  return <MemoryRouter><NotificationInterestProvider repository={repo}>
    <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
    <NotificationInterestButton target={{ type: 'REGION', id: '11', name: '서울특별시' }} source="REGION_SEARCH" />
  </NotificationInterestProvider></MemoryRouter>
}
async function click(name = '서울 단지 알림 받기') {
  const button = screen.getByRole('button', { name })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  return button
}
function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((complete, fail) => { resolve = complete; reject = fail })
  return { promise, resolve, reject }
}

it('비회원은 이전 브라우저 설정을 무시하고 로그인 안내를 닫으면 포커스를 복원한다', async () => {
  localStorage.setItem('toadzip.notification-interest.requested:COMPLEX:1', '1')
  const repo = repository(guest)
  render(example(repo))
  const button = await click()
  const dialog = await screen.findByRole('dialog', { name: '로그인이 필요해요' })
  expect(dialog).toHaveAttribute('open')
  fireEvent(dialog, new Event('cancel', { cancelable: true }))
  await waitFor(() => expect(dialog).not.toBeInTheDocument())
  expect(button).toHaveFocus()
  expect(repo.record).not.toHaveBeenCalled()
})

it('회원은 다른 기기와 새로고침에서도 서버 설정을 복원한다', async () => {
  const repo = repository({ emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
  render(example(repo))
  expect(await screen.findByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: '서울특별시 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
})

it('선택된 종을 다시 누르면 설정을 해제한다', async () => {
  const repo = repository({ emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
  render(example(repo))
  await screen.findByRole('button', { name: '서울 단지 알림 취소' })
  await click('서울 단지 알림 취소')
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false'))
  expect(repo.record).toHaveBeenCalledWith(expect.objectContaining({ eventType: 'CANCELLED' }))
  expect(screen.getByRole('status')).toHaveTextContent('알림 설정을 해제했어요.')
})

it('저장 실패는 선택 상태를 바꾸지 않고 같은 이벤트로 재시도한다', async () => {
  const repo = repository()
  repo.record.mockRejectedValueOnce(new Error('network'))
  render(example(repo))
  const button = await click()
  expect(await screen.findByRole('alert')).toHaveTextContent('알림 설정을 저장하지 못했어요.')
  expect(button).toHaveAttribute('aria-pressed', 'false')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })).toBeVisible()
  expect(repo.record.mock.calls[0]?.[0]).toEqual(repo.record.mock.calls[1]?.[0])
  expect(button).toHaveAttribute('aria-pressed', 'true')
})

it('해제 실패는 기존 설정을 유지하고 닫은 뒤 다시 해제할 수 있다', async () => {
  const repo = repository({ emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
  repo.record.mockRejectedValueOnce(new Error('network'))
  render(example(repo))
  await screen.findByRole('button', { name: '서울 단지 알림 취소' })
  const button = await click('서울 단지 알림 취소')
  expect(await screen.findByRole('alert')).toHaveTextContent('알림 해제를 완료하지 못했어요.')
  expect(button).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(button).toHaveFocus()
  await click('서울 단지 알림 취소')
  await screen.findByRole('button', { name: '서울 단지 알림 받기' })
})

it.each(['resolve', 'reject'] as const)('저장소 변경 전 요청이 %s되어도 새 세션을 덮지 않는다', async (settlement) => {
  const pending = deferred<void>()
  const previous = repository()
  previous.record.mockReturnValue(pending.promise)
  const next = repository(guest)
  const view = render(example(previous))
  await click()
  view.rerender(example(next))
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
  await act(async () => { if (settlement === 'resolve') pending.resolve(); else pending.reject(new Error('old')) })
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await click()
  expect(screen.getByRole('dialog')).toHaveTextContent('로그인한 사용자만 이용할 수 있어요.')
})

it('창으로 돌아오면 서버에서 변경된 설정을 반영한다', async () => {
  const repo = repository()
  render(example(repo))
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
  repo.loadStatus.mockResolvedValue({ emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
  fireEvent.focus(window)
  expect(await screen.findByRole('button', { name: '서울 단지 알림 취소' })).toHaveAttribute('aria-pressed', 'true')
})

it('조회 실패는 빈 목록 성공으로 처리하지 않고 재시도한다', async () => {
  const repo = repository()
  repo.loadStatus.mockRejectedValueOnce(new Error('network'))
  render(example(repo))
  expect(await screen.findByRole('alert')).toHaveTextContent('알림 상태를 불러오지 못했어요.')
  expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
})

it('저장 중 창 포커스가 돌아와도 저장 응답을 버리거나 중복 요청하지 않는다', async () => {
  const pending = deferred<void>()
  const repo = repository()
  repo.record.mockReturnValue(pending.promise)
  render(example(repo))
  const button = await click()
  fireEvent.focus(window)
  fireEvent.click(button)
  await act(async () => pending.resolve())
  expect(repo.record).toHaveBeenCalledOnce()
  expect(button).toHaveAttribute('aria-pressed', 'true')
})

it('준비 중 모달은 키보드 포커스를 가두고 Escape로 닫는다', async () => {
  render(example(repository()))
  const button = await click()
  const dialog = await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })
  const confirm = screen.getByRole('button', { name: '확인' })
  expect(confirm).toHaveFocus()
  fireEvent.keyDown(confirm, { key: 'Tab' })
  expect(confirm).toHaveFocus()
  fireEvent.keyDown(dialog, { key: 'Escape' })
  expect(button).toHaveFocus()
})

it('브라우저 저장소가 차단돼도 회원 설정 저장은 동작한다', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  render(example(repository()))
  await click()
  expect(await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })).toBeVisible()
})

it('실제 노출만 기록하고 같은 세션의 반복 노출을 중복하지 않는다', async () => {
  const observers: Array<(entries: Array<{ isIntersecting: boolean }>) => void> = []
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) { observers.push(callback) }
    observe() {} disconnect() {}
  })
  const repo = repository()
  const view = render(example(repo))
  await act(async () => { observers[0]?.([{ isIntersecting: false }]) })
  expect(repo.record).not.toHaveBeenCalled()
  await act(async () => { observers[0]?.([{ isIntersecting: true }]) })
  expect(repo.record).toHaveBeenCalledOnce()
  view.unmount()
  render(example(repo))
  await act(async () => { observers[2]?.([{ isIntersecting: true }]) })
  expect(repo.record).toHaveBeenCalledOnce()
})

it('전체 해제 도중 세션이 바뀌면 나머지 회원 설정 요청을 중단한다', async () => {
  const { InterestContext } = await import('./NotificationInterestContext')
  const pending = deferred<void>()
  const previous = repository({ emailConfirmed: false, targets: [
    { targetType: 'COMPLEX', targetId: '1' }, { targetType: 'REGION', targetId: '11' },
  ] })
  previous.record.mockReturnValue(pending.promise)
  const next = repository(guest)
  const content = (repo: NotificationInterestRepository) => <MemoryRouter><NotificationInterestProvider repository={repo}>
    <InterestContext.Consumer>{(context) => <button type="button" disabled={context?.blocked}
      onClick={(event) => context?.clearAll(event.currentTarget)}>전체 해제</button>}</InterestContext.Consumer>
  </NotificationInterestProvider></MemoryRouter>
  const view = render(content(previous))
  await waitFor(() => expect(screen.getByRole('button')).toBeEnabled())
  fireEvent.click(screen.getByRole('button'))
  view.rerender(content(next))
  await waitFor(() => expect(screen.getByRole('button')).toBeEnabled())
  await act(async () => pending.resolve())
  expect(previous.record).toHaveBeenCalledOnce()
  expect(next.record).not.toHaveBeenCalled()
  expect(screen.queryByText('알림 설정을 모두 해제했어요.')).not.toBeInTheDocument()
})

it('전체 해제 중 탭을 떠나면 나머지 요청을 중단하고 돌아올 때 상태를 다시 확인한다', async () => {
  const { InterestContext } = await import('./NotificationInterestContext')
  const pending = deferred<void>()
  const repo = repository({ emailConfirmed: false, targets: [
    { targetType: 'COMPLEX', targetId: '1' }, { targetType: 'REGION', targetId: '11' },
  ] })
  repo.record.mockReturnValue(pending.promise)
  render(<MemoryRouter><NotificationInterestProvider repository={repo}>
    <InterestContext.Consumer>{(context) => <button type="button" disabled={context?.blocked}
      onClick={(event) => context?.clearAll(event.currentTarget)}>전체 해제</button>}</InterestContext.Consumer>
  </NotificationInterestProvider></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button')).toBeEnabled())
  fireEvent.click(screen.getByRole('button'))
  fireEvent.blur(window)
  repo.loadStatus.mockResolvedValue(guest)
  await act(async () => pending.resolve())
  fireEvent.focus(window)
  await waitFor(() => expect(repo.loadStatus).toHaveBeenCalledTimes(2))
  expect(repo.record).toHaveBeenCalledOnce()
  expect(screen.queryByText('알림 설정을 모두 해제했어요.')).not.toBeInTheDocument()
})
