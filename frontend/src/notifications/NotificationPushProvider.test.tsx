import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { NotificationInboxContext } from './NotificationInboxContext'
import { NotificationPushProvider } from './NotificationPushProvider'
import { useNotificationPush } from './NotificationPushContext'
import { usePushClientState } from './usePushClientState'
import { notificationRepository } from './notificationRepository'
import { pushDeviceRepository, type PushDeviceState } from './pushDeviceRepository'
import { preparePushSdk } from './firebasePushSdk'
vi.mock('./firebasePushSdk', () => ({ pushUiEnabled: true, preparePushSdk: vi.fn() }))
afterEach(() => vi.restoreAllMocks())
const invalidate = vi.fn()
const initial: PushDeviceState = { deviceId: 'device', bindingVersion: '1', enabled: true, knownForUser: true }
function Probe() {
  const push = useNotificationPush()
  const state = usePushClientState(push?.client)
  return <div><span data-testid="status">{state.status}</span><span data-testid="known">{String(push?.current?.knownForUser)}</span><span>{push?.error}</span></div>
}
function App({ userId = '101' }: { readonly userId?: string }) {
  return <NotificationInboxContext.Provider value={{ userId, repository: notificationRepository, unreadCount: 0, error: null, revision: 0, invalidate, sessionExpired: vi.fn() }}><NotificationPushProvider><Probe /></NotificationPushProvider></NotificationInboxContext.Provider>
}
function sdk() {
  let registered: (fid: string) => void = () => {}
  vi.mocked(preparePushSdk).mockResolvedValue({ permission: () => 'granted', requestPermission: vi.fn(), register: async () => registered('fid'), onRegistered: listener => { registered = listener; return () => {} }, onMessage: () => () => {} })
}
it('일반 iPhone은 SDK를 준비하지 않고도 현재 기기 여부를 조회해 안내를 결정한다', async () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone OS 18_0')
  vi.spyOn(pushDeviceRepository, 'current').mockResolvedValue({ deviceId: null, enabled: false, bindingVersion: null, knownForUser: false })
  vi.mocked(preparePushSdk).mockClear()
  render(<App />)
  await waitFor(() => expect(screen.getByTestId('known')).toHaveTextContent('false'))
  expect(preparePushSdk).not.toHaveBeenCalled()
})
it('열려 있던 기기로 돌아오면 원격 해제를 반영하고 SDK를 다시 등록하지 않는다', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  sdk()
  vi.spyOn(pushDeviceRepository, 'current').mockResolvedValue(initial)
  const register = vi.spyOn(pushDeviceRepository, 'register').mockResolvedValue(initial)
  // 상태 표시뿐 아니라 복귀 이벤트를 등록하는 effect까지 완료한 뒤 이벤트를 보낸다.
  await act(async () => { render(<App />) })
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('on'))
  vi.mocked(pushDeviceRepository.current).mockResolvedValue({ ...initial, bindingVersion: '2', enabled: false })
  await act(async () => { fireEvent.focus(window) })
  expect(pushDeviceRepository.current).toHaveBeenCalledTimes(2)
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('off'))
  expect(register).toHaveBeenCalledOnce()
})
it('계정 전환 전 시작한 응답이 늦게 와도 새 계정의 클라이언트를 바꾸지 않는다', async () => {
  sdk()
  let finish!: (state: PushDeviceState) => void
  vi.spyOn(pushDeviceRepository, 'current').mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue({ ...initial, enabled: false })
  const register = vi.spyOn(pushDeviceRepository, 'register').mockResolvedValue(initial)
  const view = render(<App />)
  view.rerender(<App userId="102" />)
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('off'))
  await act(async () => finish(initial))
  expect(screen.getByTestId('status')).toHaveTextContent('off')
  expect(register).not.toHaveBeenCalled()
})

it('겹친 복귀 이벤트의 기기 조회를 한 번으로 합치고 실패 뒤에는 다시 조회한다', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  sdk()
  const current = vi.spyOn(pushDeviceRepository, 'current').mockResolvedValue(initial)
  vi.spyOn(pushDeviceRepository, 'register').mockResolvedValue(initial)
  await act(async () => { render(<App />) })
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('on'))
  let reject!: (reason: Error) => void
  current.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail }))
  fireEvent.focus(window)
  fireEvent(document, new Event('visibilitychange'))
  fireEvent.online(window)
  expect(current).toHaveBeenCalledTimes(2)
  await act(async () => reject(new Error('offline')))
  current.mockResolvedValue({ ...initial, enabled: false, bindingVersion: '2' })
  await act(async () => fireEvent.focus(window))
  expect(current).toHaveBeenCalledTimes(3)
  expect(screen.getByTestId('status')).toHaveTextContent('off')
})

it('오프라인과 숨겨진 탭에서는 기기 자동 조회를 보내지 않는다', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  sdk()
  const current = vi.spyOn(pushDeviceRepository, 'current').mockResolvedValue(initial)
  vi.spyOn(pushDeviceRepository, 'register').mockResolvedValue(initial)
  await act(async () => { render(<App />) })
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('on'))
  online.mockReturnValue(false)
  fireEvent.focus(window)
  fireEvent(document, new Event('visibilitychange'))
  online.mockReturnValue(true)
  visibility.mockReturnValue('hidden')
  fireEvent.focus(window)
  fireEvent.online(window)
  expect(current).toHaveBeenCalledTimes(1)
  visibility.mockReturnValue('visible')
  await act(async () => fireEvent(document, new Event('visibilitychange')))
  expect(current).toHaveBeenCalledTimes(2)
})

it('화면을 떠나기 전 조회가 늦어져도 복귀 후 최신 기기 상태를 다시 가져온다', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  sdk()
  const current = vi.spyOn(pushDeviceRepository, 'current').mockResolvedValue(initial)
  vi.spyOn(pushDeviceRepository, 'register').mockResolvedValue(initial)
  await act(async () => { render(<App />) })
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('on'))
  let finish!: (state: PushDeviceState) => void
  current.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  fireEvent.focus(window)
  fireEvent.blur(window)
  current.mockResolvedValue({ ...initial, enabled: false, bindingVersion: '2' })
  await act(async () => fireEvent.focus(window))
  expect(current).toHaveBeenCalledTimes(3)
  expect(screen.getByTestId('status')).toHaveTextContent('off')
  await act(async () => finish(initial))
  expect(screen.getByTestId('status')).toHaveTextContent('off')
})
