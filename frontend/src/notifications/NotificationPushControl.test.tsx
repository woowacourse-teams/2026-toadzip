import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { NotificationPushControl } from './NotificationPushControl'
import { NotificationPushContext } from './NotificationPushContext'
import { createPushClient, type PushSdk } from './firebasePushClient'
import type { PushDeviceState } from './pushDeviceRepository'

vi.mock('./firebasePushSdk', () => ({ pushUiEnabled: true }))
afterEach(() => vi.restoreAllMocks())
it('iPhone 일반 브라우저에서는 알림 받기를 누르면 설치 방법을 안내하고 닫으면 버튼으로 돌아온다', () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1')
  render(<NotificationPushContext.Provider value={{ client: null, error: '이 브라우저에서는 푸시 알림을 지원하지 않아요.', retry: vi.fn() }}><NotificationPushControl /></NotificationPushContext.Provider>)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  const enable = screen.getByRole('switch', { name: '이 기기 알림 받기' })
  enable.focus(); fireEvent.click(enable)
  const dialog = screen.getByRole('dialog', { name: 'iPhone에서 알림 받기' })
  expect(within(dialog).getByText(/홈 화면에 추가/)).toBeVisible()
  expect(within(dialog).getByText('Safari에서 복덕방 열기')).toBeVisible()
  expect(within(dialog).getByText('공유 버튼 누르기')).toBeVisible()
  fireEvent.click(within(dialog).getByRole('button', { name: '확인했어요' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(enable).toHaveFocus()
})
it('서버에 등록을 저장하는 동안 진행 상태를 표시하고 저장 후 완료를 안내한다', async () => {
  let notify!: (fid: string) => void
  let save!: (state: PushDeviceState) => void
  const sdk: PushSdk = { permission: () => 'granted', requestPermission: async () => 'granted', register: async () => notify('fid'), onRegistered: listener => { notify = listener; return () => {} }, onMessage: () => () => {} }
  const client = createPushClient('1', sdk, { current: async () => ({ deviceId: null, bindingVersion: null, enabled: false }), register: () => new Promise(resolve => { save = resolve }), disable: async () => {} })
  await client.start()
  render(<NotificationPushContext.Provider value={{ client, error: '', retry: vi.fn() }}><NotificationPushControl /></NotificationPushContext.Provider>)
  fireEvent.click(screen.getByRole('switch', { name: '이 기기 알림 받기' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/연결/))
  expect(screen.getByRole('switch', { name: '이 기기 알림 받기' })).toBeDisabled()
  expect(screen.getByRole('switch')).not.toBeChecked()
  await act(async () => save({ deviceId: 'device', enabled: true, bindingVersion: '1' }))
  expect(screen.getByRole('status')).toHaveTextContent(/완료/)
  expect(screen.getByRole('switch', { name: '이 기기 알림 받기' })).toBeChecked()
  fireEvent.click(screen.getByRole('switch'))
  await waitFor(() => expect(screen.getByRole('switch')).not.toBeChecked())
  expect(screen.getByRole('status')).toHaveTextContent('알림을 껐어요')
  client.dispose()
})
it('준비 오류는 새로고침 대신 같은 화면에서 다시 시도할 수 있다', () => {
  const retry = vi.fn()
  render(<NotificationPushContext.Provider value={{ client: null, error: '네트워크 오류', retry }}><NotificationPushControl /></NotificationPushContext.Provider>)
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(retry).toHaveBeenCalledOnce()
})

it('허용 버튼을 눌렀을 때만 권한을 요청하고 거부 안내를 보인다', async () => {
  const request = vi.fn(async () => 'denied' as const)
  const client = createPushClient('1', { permission: () => 'default', requestPermission: request, register: vi.fn(), onMessage: () => () => {}, onRegistered: () => () => {} }, { current: async () => ({ deviceId: null, enabled: false, bindingVersion: null }), register: vi.fn(), disable: vi.fn() })
  await client.start()
  render(<NotificationPushContext.Provider value={{ client, error: '', retry: vi.fn() }}><NotificationPushControl /></NotificationPushContext.Provider>)
  expect(request).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('switch', { name: '이 기기 알림 받기' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('브라우저 설정'))
  expect(request).toHaveBeenCalledOnce()
  client.dispose()
})
it('미지원 또는 설정 누락 시 등록 버튼 없이 이유와 재시도를 제공한다', () => {
  render(<NotificationPushContext.Provider value={{ client: null, error: '지원하지 않는 브라우저예요.', retry: vi.fn() }}><NotificationPushControl /></NotificationPushContext.Provider>)
  expect(screen.getByRole('alert')).toHaveTextContent('지원하지 않는 브라우저')
  expect(screen.queryByRole('switch', { name: '이 기기 알림 받기' })).not.toBeInTheDocument()
})
