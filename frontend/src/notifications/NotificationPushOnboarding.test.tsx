import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { NotificationPushOnboarding } from './NotificationPushOnboarding'
import { NotificationPushContext } from './NotificationPushContext'
import { InterestContext } from '../public-housing/interest/NotificationInterestContext'
import { createPushClient, type PushSdk } from './firebasePushClient'
import type { PushDeviceState } from './pushDeviceRepository'

vi.mock('./firebasePushSdk', () => ({ pushUiEnabled: true }))
beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())
const unknown: PushDeviceState = { deviceId: null, enabled: false, bindingVersion: null, knownForUser: false }
async function setup(current = unknown, userId = '101', hasInterests = true, permission: NotificationPermission = 'default') {
  let registered: (fid: string) => void = () => {}
  const requestPermission = vi.fn(async () => 'granted' as const)
  const sdk: PushSdk = { permission: () => permission, requestPermission,
    register: async () => registered('fid'), onRegistered: listener => { registered = listener; return () => {} }, onMessage: () => () => {} }
  const register = vi.fn(async () => ({ deviceId: 'device', enabled: true, bindingVersion: '1' }))
  const client = createPushClient(userId, sdk, { current: async () => current, register, disable: vi.fn() })
  await client.start(current)
  const ui = <InterestContext.Provider value={{ userId, mode: 'member', blocked: false, clearingAll: false, batchError: '', requested: new Map(), targets: hasInterests ? [{ type: 'REGION', id: '1', name: '마포구' }] : [], clearAll: vi.fn(), resetSession: vi.fn(), refreshStatus: vi.fn(), request: vi.fn(), expose: vi.fn() }}>
    <NotificationPushContext.Provider value={{ client, current, error: '', retry: vi.fn() }}><NotificationPushOnboarding /></NotificationPushContext.Provider>
  </InterestContext.Provider>
  return { ...render(ui), client, requestPermission, register }
}
it('관심 알림이 있는 새 기기에 안내하고 직접 클릭한 후 등록 완료를 보여준다', async () => {
  const view = await setup()
  expect(await screen.findByRole('dialog', { name: '이 기기에서도 알림을 받을까요?' })).toBeVisible()
  expect(view.requestPermission).not.toHaveBeenCalled()
  screen.getByRole('button', { name: '이 기기에서 알림 받기' }).focus()
  fireEvent.click(screen.getByRole('button', { name: '이 기기에서 알림 받기' }))
  await waitFor(() => expect(screen.getByText('알림 설정이 완료됐어요')).toBeVisible())
  expect(screen.getByRole('heading', { name: '알림 설정이 완료됐어요' })).toHaveFocus()
  expect(view.requestPermission).toHaveBeenCalledOnce()
  expect(view.register).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '확인' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  view.client.dispose()
})
it('나중에 선택은 새 로그인에도 유지하고 다른 계정에는 적용하지 않는다', async () => {
  const first = await setup()
  fireEvent.click(await screen.findByRole('button', { name: '나중에' }))
  first.unmount(); first.client.dispose()
  const second = await setup()
  await act(async () => {})
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  second.unmount(); second.client.dispose()
  const other = await setup(unknown, '102')
  expect(await screen.findByRole('dialog')).toBeVisible()
  other.client.dispose()
})
it.each([
  [{ ...unknown, knownForUser: true }, true],
  [unknown, false],
  [{ deviceId: 'device', enabled: true, bindingVersion: '1', knownForUser: true }, true],
] as const)('직접 끈 기기·관심 없음·기존 기기에는 권하지 않는다: %j', async (current, interests) => {
  const view = await setup(current, '101', interests)
  await act(async () => {})
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  view.client.dispose()
})
it('일반 iPhone에서는 버튼을 눌러 홈 화면 추가 안내로 이동한다', async () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone OS 18_0')
  const view = await setup()
  fireEvent.click(await screen.findByRole('button', { name: '이 기기에서 알림 받기' }))
  expect(screen.getByRole('dialog', { name: 'iPhone에서 알림 받기' })).toBeVisible()
  expect(screen.getByText('공유 버튼 누르기')).toBeVisible()
  expect(view.requestPermission).not.toHaveBeenCalled()
  view.client.dispose()
})
it('이미 차단된 브라우저는 권한 요청 대신 차단 해제 방법을 안내한다', async () => {
  vi.stubGlobal('Notification', { permission: 'denied' })
  const view = await setup(unknown, '101', true, 'denied')
  fireEvent.click(await screen.findByRole('button', { name: '이 기기에서 알림 받기' }))
  expect(screen.getByRole('dialog', { name: '브라우저에서 알림을 허용해 주세요' })).toBeVisible()
  expect(view.requestPermission).not.toHaveBeenCalled()
  view.client.dispose(); vi.unstubAllGlobals()
})
it('기존 모달이 닫힐 때까지 안내를 겹쳐 띄우지 않는다', async () => {
  const existing = document.createElement('dialog')
  document.body.append(existing); existing.showModal()
  const view = await setup()
  await act(async () => {})
  expect(screen.queryByRole('dialog', { name: '이 기기에서도 알림을 받을까요?' })).not.toBeInTheDocument()
  await act(async () => { existing.close(); existing.remove() })
  expect(await screen.findByRole('dialog', { name: '이 기기에서도 알림을 받을까요?' })).toBeVisible()
  view.client.dispose()
})
it('저장 공간이 차단되어도 나중에 선택은 현재 실행 중 다시 권하지 않는다', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  const first = await setup(unknown, 'storage-blocked')
  fireEvent.click(await screen.findByRole('button', { name: '나중에' }))
  first.unmount(); first.client.dispose()
  const next = await setup(unknown, 'storage-blocked')
  await act(async () => {})
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  next.client.dispose()
})
it('읽기는 되지만 저장 용량이 부족해도 같은 실행 중 나중에 선택을 기억한다', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  const first = await setup(unknown, 'storage-quota')
  fireEvent.click(await screen.findByRole('button', { name: '나중에' }))
  first.unmount(); first.client.dispose()
  const next = await setup(unknown, 'storage-quota')
  await act(async () => {})
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  next.client.dispose()
})
