import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { NotificationPushControl } from './NotificationPushControl'
import { NotificationPushContext } from './NotificationPushContext'
import { createPushClient } from './firebasePushClient'
vi.mock('./firebasePushSdk', () => ({ pushUiEnabled: true }))
afterEach(() => { localStorage.clear() })
it('허용 버튼을 눌렀을 때만 권한을 요청하고 거부 안내를 보인다', async () => {
  const request = vi.fn(async () => 'denied' as const)
  const client = createPushClient('1', { permission: () => 'default', requestPermission: request, register: vi.fn(), onMessage: () => () => {}, onRegistered: () => () => {} }, { current: async () => ({ deviceId: null, enabled: false, bindingVersion: null }), register: vi.fn(), disable: vi.fn() })
  await client.start()
  render(<NotificationPushContext.Provider value={{ client, error: '', retry: vi.fn() }}><NotificationPushControl /></NotificationPushContext.Provider>)
  expect(request).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '이 기기에서 푸시 알림 받기' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('브라우저 설정'))
  expect(request).toHaveBeenCalledOnce()
  client.dispose()
})
it('미지원 또는 설정 누락 시 등록 버튼 없이 이유와 재시도를 제공한다', () => {
  render(<NotificationPushContext.Provider value={{ client: null, error: '지원하지 않는 브라우저예요.', retry: vi.fn() }}><NotificationPushControl /></NotificationPushContext.Provider>)
  expect(screen.getByRole('alert')).toHaveTextContent('지원하지 않는 브라우저')
  expect(screen.queryByRole('button', { name: '이 기기에서 푸시 알림 받기' })).not.toBeInTheDocument()
})
