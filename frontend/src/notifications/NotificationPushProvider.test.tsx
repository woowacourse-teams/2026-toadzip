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
  const events = vi.spyOn(window, 'addEventListener')
  sdk()
  vi.spyOn(pushDeviceRepository, 'current').mockResolvedValue(initial)
  const register = vi.spyOn(pushDeviceRepository, 'register').mockResolvedValue(initial)
  render(<App />)
  await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('on'))
  await waitFor(() => expect(events).toHaveBeenCalledWith('focus', expect.any(Function)))
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
