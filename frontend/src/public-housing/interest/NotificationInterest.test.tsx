import { captureProductEvent, setProductAuthState, setReplaySensitive } from '../../analytics/productAnalytics'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'
import type { NotificationInterestEvent, NotificationInterestRepository, NotificationInterestResult, NotificationOutcome, NotificationSubscriptionStatus } from './notificationInterestRepository'

vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(), setProductAuthState: vi.fn(), setReplaySensitive: vi.fn() }))

beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); sessionStorage.clear() })
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

it.each(['ACTIVATED', 'ALREADY_ACTIVE', undefined] as const)(
  '회원 설정 결과 %s에서 실제 신규 활성화만 완료로 수집한다', async (outcome) => {
    const repo = repository()
    repo.record.mockImplementation(async (event) => outcome ? resultFor(event, outcome) : undefined)
    render(example(repo))
    await click()
    await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })
    expect(repo.record.mock.calls[0]?.[0]).toMatchObject({ eventType: 'CONFIRMED' })
    expect(repo.record.mock.calls[0]?.[0]).not.toHaveProperty('email')
    const completions = captured('notification_preregistration_completed')
    expect(completions).toHaveLength(outcome === 'ACTIVATED' ? 1 : 0)
    if (outcome === 'ACTIVATED') {
      expect(completions[0]?.[1]).toMatchObject({ target_type: 'COMPLEX', target_id: '1', completion_source: 'CONFIRMED' })
      expect(completions[0]?.[2]).toMatchObject({ dedupeKey: `notification-completed:${repo.record.mock.calls[0]?.[0].eventId}`, authState: 'member' })
    }
    expect(captured('notification_form_viewed')).toHaveLength(0)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  },
)

it.each(['CANCELLED', 'UNCHANGED', undefined] as const)('회원 해제 결과 %s에서 실제 취소만 완료로 수집한다', async (outcome) => {
  const repo = repository({ emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
  repo.record.mockImplementation(async (event) => outcome ? resultFor(event, outcome) : undefined)
  render(example(repo))
  await screen.findByRole('button', { name: '서울 단지 알림 취소' })
  await click('서울 단지 알림 취소')
  await screen.findByText('서울 단지 알림 설정을 해제했어요.')
  expect(captured('notification_cancel_completed')).toHaveLength(outcome === 'CANCELLED' ? 1 : 0)
  expect(captured('notification_cancel_requested')).toHaveLength(1)
})

it.each(['UNKNOWN', 'NOT_ACTIVATED', 'OBSERVED'] as const)('%s는 회원 설정 성공을 만들지 않고 상태를 다시 조회한다', async (outcome) => {
  const repo = repository()
  repo.record.mockImplementation(async (event) => resultFor(event, outcome))
  render(example(repo))
  await click()
  await screen.findByText('처리 결과를 확인하지 못했어요. 현재 알림 설정을 다시 확인해 주세요.')
  await waitFor(() => expect(repo.loadStatus).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(captured('notification_preregistration_completed')).toHaveLength(0)
})

it.each(['ACTIVATED', 'CANCELLED'] as const)('UI가 해제되어도 서버의 %s 결과는 원래 대상과 회원 상태로 수집한다', async (outcome) => {
  const pending = deferred<NotificationInterestResult>()
  const repo = repository({ emailConfirmed: false, targets: outcome === 'CANCELLED' ? [{ targetType: 'COMPLEX', targetId: '1' }] : [] })
  repo.record.mockReturnValue(pending.promise)
  const view = render(example(repo))
  const name = outcome === 'CANCELLED' ? '서울 단지 알림 취소' : '서울 단지 알림 받기'
  await screen.findByRole('button', { name })
  await click(name)
  const event = repo.record.mock.calls[0]?.[0]
  if (!event) throw new Error('The request should have started')
  view.unmount()
  await act(async () => pending.resolve(resultFor(event, outcome)))
  expect(captureProductEvent).toHaveBeenCalledWith(
    outcome === 'CANCELLED' ? 'notification_cancel_completed' : 'notification_preregistration_completed',
    expect.objectContaining({ target_type: 'COMPLEX', target_id: '1', server_event_id: event.eventId }),
    expect.objectContaining({ authState: 'member', pageName: 'explorer' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('회원 요청 뒤 세션이 비회원으로 초기화되어도 늦은 성공은 회원 요청으로 남기고 새 UI를 덮지 않는다', async () => {
  const pending = deferred<NotificationInterestResult>()
  const previous = repository()
  previous.record.mockReturnValue(pending.promise)
  const view = render(example(previous))
  await click()
  const event = previous.record.mock.calls[0]?.[0]
  if (!event) throw new Error('The request should have started')
  const next = repository(guest)
  view.rerender(example(next))
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toBeEnabled())
  expect(setProductAuthState).toHaveBeenLastCalledWith('guest')
  await act(async () => pending.resolve(resultFor(event, 'ACTIVATED')))
  expect(captured('notification_preregistration_completed')).toHaveLength(1)
  expect(captured('notification_preregistration_completed')[0]?.[2]).toMatchObject({ authState: 'member', pageName: 'explorer' })
  expect(screen.getByRole('button', { name: '서울 단지 알림 받기' })).toHaveAttribute('aria-pressed', 'false')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('응답 유실 재시도는 명령 ID를 유지하며 취소 후 재설정에는 새 ID를 사용한다', async () => {
  const repo = repository()
  repo.record.mockRejectedValueOnce(new Error('response lost')).mockImplementation(async (event) =>
    resultFor(event, event.eventType === 'CANCELLED' ? 'CANCELLED' : 'ACTIVATED'))
  render(example(repo))
  await click()
  fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
  fireEvent.click(await screen.findByRole('button', { name: '확인' }))
  expect(repo.record.mock.calls[0]?.[0]).toEqual(repo.record.mock.calls[1]?.[0])
  await click('서울 단지 알림 취소')
  await screen.findByRole('button', { name: '서울 단지 알림 받기' })
  await click()
  await screen.findByRole('button', { name: '확인' })
  expect(repo.record.mock.calls[1]?.[0].eventId).not.toBe(repo.record.mock.calls[2]?.[0].eventId)
  expect(repo.record.mock.calls[2]?.[0].eventId).not.toBe(repo.record.mock.calls[3]?.[0].eventId)
  expect(captured('notification_preregistration_completed')).toHaveLength(2)
  expect(captured('notification_preregistration_failed')).toHaveLength(1)
})

it('창으로 돌아온 뒤 확인된 알림 상태로 분석의 회원 상태도 갱신한다', async () => {
  const repo = repository()
  render(example(repo))
  await waitFor(() => expect(setProductAuthState).toHaveBeenLastCalledWith('member'))
  repo.loadStatus.mockResolvedValue(guest)
  fireEvent.focus(window)
  await waitFor(() => expect(setProductAuthState).toHaveBeenLastCalledWith('guest'))
})

it('알림 안내 모달의 민감 화면 보호를 닫을 때 해제한다', async () => {
  render(example(repository()))
  await click()
  await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })
  expect(setReplaySensitive).toHaveBeenCalledWith('notification_form', true)
  fireEvent.click(screen.getByRole('button', { name: '확인' }))
  expect(setReplaySensitive).toHaveBeenLastCalledWith('notification_form', false)
})

it.each([false, true])('늦은 focus 조회가 설정 여부 %s의 변경 성공을 되돌리지 않는다', async (initiallyRequested) => {
  const snapshot: NotificationSubscriptionStatus = { emailConfirmed: false,
    targets: initiallyRequested ? [{ targetType: 'COMPLEX', targetId: '1' }] : [] }
  const pending = deferred<NotificationSubscriptionStatus>()
  const repo = repository(snapshot)
  repo.loadStatus.mockResolvedValueOnce(snapshot).mockReturnValueOnce(pending.promise)
  render(example(repo))
  const beforeName = initiallyRequested ? '서울 단지 알림 취소' : '서울 단지 알림 받기'
  await screen.findByRole('button', { name: beforeName })
  await waitFor(() => expect(screen.getByRole('button', { name: beforeName })).toBeEnabled())
  fireEvent.focus(window)
  await click(beforeName)
  const afterName = initiallyRequested ? '서울 단지 알림 받기' : '서울 단지 알림 취소'
  await screen.findByRole('button', { name: afterName })
  await act(async () => pending.resolve(snapshot))
  expect(screen.getByRole('button', { name: afterName })).toHaveAttribute('aria-pressed', String(!initiallyRequested))
  expect(repo.record).toHaveBeenCalledOnce()
})

it('전체 해제는 실제 취소만 완료로 수집하고 UNKNOWN 뒤 조회한 남은 설정은 새 ID로 해제한다', async () => {
  const { InterestContext } = await import('./NotificationInterestContext')
  const snapshot: NotificationSubscriptionStatus = { emailConfirmed: false, targets: [
    { targetType: 'COMPLEX', targetId: '1' }, { targetType: 'REGION', targetId: '11' },
  ] }
  const repo = repository(snapshot)
  repo.loadStatus.mockResolvedValueOnce(snapshot).mockResolvedValue({ emailConfirmed: false,
    targets: [{ targetType: 'REGION', targetId: '11' }] })
  let unknownEventId: string | undefined
  repo.record.mockImplementation(async (event) => {
    if (event.targetType === 'REGION' && !unknownEventId) unknownEventId = event.eventId
    return resultFor(event, event.eventId === unknownEventId ? 'UNKNOWN' : 'CANCELLED')
  })
  render(<MemoryRouter><NotificationInterestProvider repository={repo}>
    <InterestContext.Consumer>{(context) => <>
      <button type="button" disabled={context?.blocked} onClick={(event) => context?.clearAll(event.currentTarget)}>전체 해제</button>
      {context?.batchError && <p role="alert">{context.batchError}</p>}
      <p>남은 설정 {context?.targets.length}</p>
    </>}</InterestContext.Consumer>
  </NotificationInterestProvider></MemoryRouter>)
  await click('전체 해제')
  await screen.findByText('처리 결과를 확인하지 못했어요. 현재 알림 설정을 다시 확인해 주세요.')
  await waitFor(() => expect(repo.loadStatus).toHaveBeenCalledTimes(2))
  expect(screen.getByText('남은 설정 1')).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(captured('notification_cancel_completed')).toHaveLength(1)
  await click('전체 해제')
  await screen.findByText('알림 설정을 모두 해제했어요.')
  expect(repo.record.mock.calls[1]?.[0].eventId).not.toBe(repo.record.mock.calls[2]?.[0].eventId)
  expect(captured('notification_cancel_completed')).toHaveLength(2)
})

it('UNKNOWN 뒤 자동 조회해도 함께 실패한 네트워크 요청은 같은 ID로 재시도한다', async () => {
  const { InterestContext } = await import('./NotificationInterestContext')
  const repo = repository({ emailConfirmed: false, targets: [
    { targetType: 'COMPLEX', targetId: '1' }, { targetType: 'REGION', targetId: '11' },
  ] })
  let failedEventId: string | undefined
  let unknownEventId: string | undefined
  repo.record.mockImplementation(async (event) => {
    if (event.targetType === 'COMPLEX' && !failedEventId) {
      failedEventId = event.eventId
      throw new Error('network')
    }
    if (event.targetType === 'REGION' && !unknownEventId) unknownEventId = event.eventId
    return resultFor(event, event.eventId === unknownEventId ? 'UNKNOWN' : 'CANCELLED')
  })
  render(<MemoryRouter><NotificationInterestProvider repository={repo}>
    <InterestContext.Consumer>{(context) => <>
      <button type="button" disabled={context?.blocked} onClick={(event) => context?.clearAll(event.currentTarget)}>전체 해제</button>
      {context?.batchError && <p role="alert">{context.batchError}</p>}
    </>}</InterestContext.Consumer>
  </NotificationInterestProvider></MemoryRouter>)
  await click('전체 해제')
  expect(await screen.findByRole('alert')).toHaveTextContent('1개 설정을 해제하지 못했어요.')
  expect(repo.loadStatus).toHaveBeenCalledTimes(2)
  expect(captured('notification_cancel_completed')).toHaveLength(0)
  await click('전체 해제')
  await screen.findByText('알림 설정을 모두 해제했어요.')
  expect(repo.record.mock.calls[0]?.[0].eventId).toBe(repo.record.mock.calls[2]?.[0].eventId)
  expect(repo.record.mock.calls[1]?.[0].eventId).not.toBe(repo.record.mock.calls[3]?.[0].eventId)
  expect(captured('notification_cancel_completed')).toHaveLength(2)
})

it('전체 해제 도중 세션이 바뀌어도 이미 성공한 취소만 원래 회원 맥락으로 기록한다', async () => {
  const { InterestContext } = await import('./NotificationInterestContext')
  const pending = deferred<NotificationInterestResult>()
  const previous = repository({ emailConfirmed: false, targets: [
    { targetType: 'COMPLEX', targetId: '1' }, { targetType: 'REGION', targetId: '11' },
  ] })
  previous.record.mockReturnValue(pending.promise)
  const content = (repo: NotificationInterestRepository) => <MemoryRouter><NotificationInterestProvider repository={repo}>
    <InterestContext.Consumer>{(context) => <button type="button" disabled={context?.blocked}
      onClick={(event) => context?.clearAll(event.currentTarget)}>전체 해제</button>}</InterestContext.Consumer>
  </NotificationInterestProvider></MemoryRouter>
  const view = render(content(previous))
  await click('전체 해제')
  const event = previous.record.mock.calls[0]?.[0]
  const signal = previous.record.mock.calls[0]?.[1]
  if (!event) throw new Error('The request should have started')
  view.rerender(content(repository(guest)))
  await waitFor(() => expect(signal?.aborted).toBe(true))
  await act(async () => pending.resolve(resultFor(event, 'CANCELLED')))
  expect(previous.record).toHaveBeenCalledOnce()
  expect(captured('notification_cancel_completed')).toHaveLength(1)
  expect(captured('notification_cancel_completed')[0]?.[2]).toMatchObject({ authState: 'member' })
  expect(screen.queryByText('알림 설정을 모두 해제했어요.')).not.toBeInTheDocument()
})

function resultFor(event: NotificationInterestEvent, outcome: NotificationOutcome): NotificationInterestResult {
  return { eventId: event.eventId, targetType: event.targetType, targetId: event.targetId, outcome, occurredAt: '2026-10-08T00:00:00Z' }
}
function captured(eventName: string) {
  return vi.mocked(captureProductEvent).mock.calls.filter(([name]) => name === eventName)
}

it('관리 화면에서 시작한 늦은 취소는 이동 후에도 원래 페이지 맥락으로 수집한다', async () => {
  const pending = deferred<NotificationInterestResult>()
  const repo = repository({ emailConfirmed: false, targets: [{ targetType: 'COMPLEX', targetId: '1' }] })
  repo.record.mockReturnValue(pending.promise)
  window.history.replaceState({}, '', '/mypage/notifications')
  try {
    render(example(repo))
    await screen.findByRole('button', { name: '서울 단지 알림 취소' })
    await click('서울 단지 알림 취소')
    const event = repo.record.mock.calls[0]?.[0]
    if (!event) throw new Error('The request should have started')
    window.history.replaceState({}, '', '/')
    await act(async () => pending.resolve(resultFor(event, 'CANCELLED')))
    expect(captured('notification_cancel_completed')[0]?.[2]).toMatchObject({ pageName: 'notification_settings' })
  } finally {
    window.history.replaceState({}, '', '/')
  }
})

it('다른 모달에 가려진 알림 버튼은 모달이 닫혀 실제로 보일 때 노출을 수집한다', async () => {
  const observers: Array<(entries: Array<{ isIntersecting: boolean }>) => void> = []
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) { observers.push(callback) }
    observe() {} disconnect() {}
  })
  const repo = repository()
  const modal = document.createElement('dialog')
  modal.open = true
  document.body.append(modal)
  try {
    render(example(repo))
    await act(async () => { observers[0]?.([{ isIntersecting: true }]) })
    expect(repo.record).not.toHaveBeenCalled()
    expect(captured('notification_cta_viewed')).toHaveLength(0)
    await act(async () => { modal.remove() })
    expect(repo.record).toHaveBeenCalledOnce()
    expect(captured('notification_cta_viewed')).toHaveLength(1)
  } finally {
    modal.remove()
  }
})
