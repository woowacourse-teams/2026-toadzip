import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { NotificationDeviceList } from './NotificationDeviceList'
import { NotificationInboxContext } from './NotificationInboxContext'
import { notificationRepository } from './notificationRepository'
import { pushDeviceRepository, type PushDeviceSummary } from './pushDeviceRepository'
vi.mock('./firebasePushSdk', () => ({ pushUiEnabled: true }))
afterEach(() => vi.restoreAllMocks())
const current: PushDeviceSummary = { deviceId: 'current', bindingVersion: '1', platform: 'MAC', browser: 'CHROME', lastSeenAt: new Date().toISOString(), current: true, enabled: true, resumeAfterLogin: false }
const other: PushDeviceSummary = { ...current, deviceId: 'other', current: false, platform: 'IOS', browser: 'HOME_SCREEN', enabled: false, resumeAfterLogin: true }
function renderList() {
  return render(<NotificationInboxContext.Provider value={{ userId: '101', repository: notificationRepository, unreadCount: 0, error: null, revision: 0, invalidate: vi.fn(), sessionExpired: vi.fn() }}><NotificationDeviceList /></NotificationInboxContext.Provider>)
}
it('현재 기기·일시 중지 기기·마지막 사용을 표시하고 다른 기기만 해제한다', async () => {
  vi.spyOn(pushDeviceRepository, 'devices').mockResolvedValueOnce([current, other]).mockResolvedValue([current])
  const disable = vi.spyOn(pushDeviceRepository, 'disableDevice').mockResolvedValue()
  renderList()
  fireEvent.click(screen.getByRole('button', { name: '알림 받는 기기' }))
  expect(await screen.findByText('Mac · Chrome')).toBeVisible()
  expect(screen.getByText('현재 사용 중')).toBeVisible()
  expect(screen.getAllByText('마지막 사용 · 오늘')).toHaveLength(2)
  expect(screen.getByText('로그아웃으로 일시 중지')).toBeVisible()
  expect(screen.getAllByRole('button', { name: /알림 해제/ })).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' }))
  await waitFor(() => expect(disable).toHaveBeenCalledWith('other', '101', '1', expect.any(AbortSignal)))
  expect(await screen.findByRole('status')).toHaveTextContent('iPhone · 홈 화면 앱의 알림을 껐어요')
  expect(screen.queryByRole('button', { name: /알림 해제/ })).not.toBeInTheDocument()
  expect(screen.getByText('Mac · Chrome')).toBeVisible()
})
it('조회 실패를 빈 목록으로 위장하지 않고 다시 조회한다', async () => {
  vi.spyOn(pushDeviceRepository, 'devices').mockRejectedValueOnce(new Error('offline')).mockResolvedValue([])
  renderList()
  fireEvent.click(screen.getByRole('button', { name: '알림 받는 기기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('불러오지 못했어요')
  fireEvent.click(screen.getByRole('button', { name: '기기 목록 다시 조회' }))
  expect(await screen.findByText('알림을 받는 기기가 없어요.')).toBeVisible()
})
it('해제 실패 시 성공 안내 없이 최신 목록을 다시 확인하게 한다', async () => {
  vi.spyOn(pushDeviceRepository, 'devices').mockResolvedValue([other])
  vi.spyOn(pushDeviceRepository, 'disableDevice').mockRejectedValue(new Error('conflict'))
  renderList()
  fireEvent.click(screen.getByRole('button', { name: '알림 받는 기기' }))
  fireEvent.click(await screen.findByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('해제하지 못했어요')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(screen.getByText('iPhone · 홈 화면 앱')).toBeVisible()
  await act(async () => {})
})
it('해제 직전 시작된 조회가 늦게 끝나도 해제한 기기를 되살리지 않는다', async () => {
  let finish!: (devices: readonly PushDeviceSummary[]) => void
  const list = vi.spyOn(pushDeviceRepository, 'devices').mockResolvedValueOnce([other]).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  vi.spyOn(pushDeviceRepository, 'disableDevice').mockResolvedValue()
  renderList()
  fireEvent.click(screen.getByRole('button', { name: '알림 받는 기기' }))
  await screen.findByText('iPhone · 홈 화면 앱')
  fireEvent.click(screen.getByRole('button', { name: '기기 목록 다시 조회' }))
  await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' })).not.toBeInTheDocument())
  await act(async () => finish([other]))
  expect(screen.queryByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' })).not.toBeInTheDocument()
})

it('기본은 접혀 있고 펼칠 때만 조회하며 작은 안내 문구는 표시하지 않는다', async () => {
  const list = vi.spyOn(pushDeviceRepository, 'devices').mockResolvedValue([current])
  renderList()
  const disclosure = screen.getByRole('button', { name: '알림 받는 기기' })
  expect(disclosure).toHaveAttribute('aria-expanded', 'false')
  expect(list).not.toHaveBeenCalled()
  expect(screen.queryByText('Mac · Chrome')).not.toBeInTheDocument()
  fireEvent.click(disclosure)
  expect(await screen.findByText('Mac · Chrome')).toBeVisible()
  expect(disclosure).toHaveAttribute('aria-expanded', 'true')
  expect(screen.queryByText(/위 토글/)).not.toBeInTheDocument()
  fireEvent.click(disclosure)
  expect(disclosure).toHaveAttribute('aria-expanded', 'false')
  expect(screen.queryByText('Mac · Chrome')).not.toBeInTheDocument()
})


it('기기 해제는 즉시 숨기고 실패하면 다시 표시한다', async () => {
  let reject!: (error: Error) => void
  vi.spyOn(pushDeviceRepository, 'devices').mockResolvedValue([current, other])
  const disable = vi.spyOn(pushDeviceRepository, 'disableDevice').mockImplementation(() => new Promise<void>((_, fail) => { reject = fail }))
  renderList()
  fireEvent.click(screen.getByRole('button', { name: '알림 받는 기기' }))
  fireEvent.click(await screen.findByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' }))
  expect(screen.queryByText('iPhone · 홈 화면 앱')).not.toBeInTheDocument()
  expect(screen.queryByText('해제 중…')).not.toBeInTheDocument()
  expect(screen.getByText('Mac · Chrome')).toBeVisible()
  expect(disable).toHaveBeenCalledOnce()
  await act(async () => reject(new Error('offline')))
  expect(screen.getByText('iPhone · 홈 화면 앱')).toBeVisible()
  expect(screen.getByRole('button', { name: 'iPhone · 홈 화면 앱 알림 해제' })).toBeEnabled()
  expect(screen.getByRole('alert')).toHaveTextContent('해제하지 못했어요')
})
