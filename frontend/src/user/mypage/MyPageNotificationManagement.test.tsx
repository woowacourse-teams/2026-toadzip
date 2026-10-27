import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { InterestContext } from '../../public-housing/interest/NotificationInterestContext'
import { NotificationPushContext } from '../../notifications/NotificationPushContext'
import { NotificationInboxContext } from '../../notifications/NotificationInboxContext'
import { notificationRepository } from '../../notifications/notificationRepository'
import { pushDeviceRepository } from '../../notifications/pushDeviceRepository'
import type { PushClient } from '../../notifications/firebasePushClient'
import { BookmarkProvider } from '../bookmarks/BookmarkProvider'
import { MyPageView } from './MyPageView'

vi.mock('../../notifications/firebasePushSdk', () => ({ pushUiEnabled: true }))
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
function Location() { const location = useLocation(); return <output aria-label="주소">{location.search}</output> }
function setup(status: 'on' | 'off' = 'off', path = '/mypage/notifications') {
  const clearAll = vi.fn()
  const refreshStatus = vi.fn()
  const client: PushClient = { start: vi.fn(), refresh: vi.fn(), invalidateRefresh: vi.fn(), enable: vi.fn(), disable: vi.fn(), afterLogout: vi.fn(),
    subscribe: () => () => {}, getSnapshot: () => snapshot, subscribeRefresh: () => () => {}, dispose: vi.fn() }
  const snapshot = { status, message: '' }
  const devices = vi.spyOn(pushDeviceRepository, 'devices').mockResolvedValue([])
  render(<MemoryRouter initialEntries={[path]}>
    <BookmarkProvider repository={{ list: async () => [], save: vi.fn(), remove: vi.fn() }} loadUser={async () => ({ id: 7, email: null })}>
      <InterestContext value={{ userId: '7', mode: 'member', blocked: false, clearingAll: false, batchError: '', clearAll, retrying: false,
        targets: [{ type: 'COMPLEX', id: '1', name: '첫 단지' }, { type: 'COMPLEX', id: '2', name: '둘째 단지' }, { type: 'ANNOUNCEMENT', id: '1', name: '공고', status: 'APPLYING', applicationStartDate: '2026-10-20', applicationEndDate: '2026-10-30' }, { type: 'REGION', id: '11', name: '서울' }],
        requested: new Map(), resetSession: vi.fn(), refreshStatus, request: vi.fn(), expose: vi.fn() }}>
        <NotificationInboxContext value={{ userId: '7', repository: notificationRepository, unreadCount: 0, error: null, revision: 0, invalidate: vi.fn(), sessionExpired: vi.fn() }}>
          <NotificationPushContext value={{ client, error: '', retry: vi.fn() }}>
            <MyPageView onOpenComplex={vi.fn()} onOpenAnnouncement={vi.fn()} onBrowseAnnouncements={vi.fn()} onClose={vi.fn()} /><Location />
          </NotificationPushContext>
        </NotificationInboxContext>
      </InterestContext>
    </BookmarkProvider>
  </MemoryRouter>)
  return { clearAll, client, devices, refreshStatus }
}

it('목록에서는 상단 알림 켜기만 보이며 기기 탭에서 수신 설정과 기기 목록을 바로 보여준다', async () => {
  const { client, devices } = setup()
  const enable = await screen.findByRole('button', { name: '이 기기 알림 켜기' })
  expect(screen.queryByRole('region', { name: '알림 받는 기기' })).not.toBeInTheDocument()
  expect(devices).not.toHaveBeenCalled()
  expect(client.enable).not.toHaveBeenCalled()
  fireEvent.click(enable)
  expect(client.enable).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('tab', { name: '기기 관리' }))
  expect(screen.getByLabelText('주소')).toHaveTextContent('?type=DEVICES')
  expect(screen.getByRole('switch', { name: '이 기기 알림 받기' })).toBeInTheDocument()
  expect(screen.queryByText('이 기기에만 적용')).not.toBeInTheDocument()
  expect(screen.queryByText(/직접 로그아웃하면/)).not.toBeInTheDocument()
  expect(await screen.findByText('알림을 받는 기기가 없어요.')).toBeVisible()
  expect(screen.queryByRole('button', { name: '알림 받는 기기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '알림 해제' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: '지역 1' }))
  expect(screen.queryByRole('region', { name: '알림 받는 기기' })).not.toBeInTheDocument()
})

it('알림 켜짐 버튼은 알림을 끄지 않고 기기 탭으로 이동해 포커스를 옮긴다', async () => {
  const { client } = setup('on')
  fireEvent.click(await screen.findByRole('button', { name: '알림 켜짐' }))
  expect(screen.getByRole('tab', { name: '기기 관리' })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('tab', { name: '기기 관리' })).toHaveFocus()
  expect(client.disable).not.toHaveBeenCalled()
})

it('기기 탭 직접 접근과 키보드 이동을 지원한다', async () => {
  setup('off', '/mypage/notifications?type=DEVICES')
  const tab = await screen.findByRole('tab', { name: '기기 관리' })
  expect(tab).toHaveAttribute('aria-selected', 'true')
  fireEvent.keyDown(tab, { key: 'Home' })
  expect(screen.getByRole('tab', { name: '단지 2' })).toHaveFocus()
  fireEvent.keyDown(document.activeElement!, { key: 'End' })
  expect(tab).toHaveFocus()
})

it('체크 일부·전체 선택과 취소를 지원하고 탭 전환 시 선택과 확인을 초기화한다', async () => {
  const { clearAll } = setup()
  const first = await screen.findByRole('checkbox', { name: '첫 단지 선택' })
  expect(screen.queryByRole('button', { name: /알림 끄기|^알림 해제$/ })).not.toBeInTheDocument()
  fireEvent.click(first)
  expect(screen.getByRole('checkbox', { name: '전체 선택' })).toBePartiallyChecked()
  expect(screen.getByRole('button', { name: '알림 끄기 (1)' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: /전체 해제|^알림 해제$/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  expect(screen.getByRole('checkbox', { name: '둘째 단지 선택' })).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '알림 끄기 (2)' }))
  const confirm = screen.getByRole('group', { name: '알림 해제 확인' })
  expect(confirm).toHaveTextContent('단지 알림 2개')
  fireEvent.click(within(confirm).getByRole('button', { name: '취소' }))
  expect(clearAll).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('checkbox', { name: '둘째 단지 선택' }))
  expect(screen.getByRole('button', { name: '알림 끄기 (1)' })).toBeEnabled()
  fireEvent.click(first)
  expect(screen.queryByRole('button', { name: /알림 끄기|^알림 해제$/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(screen.getByRole('button', { name: '알림 끄기 (2)' }))
  fireEvent.click(screen.getByRole('tab', { name: '공고 1' }))
  expect(screen.queryByRole('group', { name: '알림 해제 확인' })).not.toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: '공고 선택' })).not.toBeChecked()
  fireEvent.click(screen.getByRole('tab', { name: '단지 2' }))
  expect(screen.getByRole('checkbox', { name: '첫 단지 선택' })).not.toBeChecked()
  expect(screen.queryByRole('button', { name: /알림 끄기|^알림 해제$/ })).not.toBeInTheDocument()
})


it('차단된 알림 권한은 버튼을 누른 뒤에만 안내하고 다시 권한을 요청하지 않는다', async () => {
  vi.stubGlobal('Notification', { permission: 'denied' })
  const { client } = setup()
  const enable = await screen.findByRole('button', { name: '이 기기 알림 켜기' })
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  enable.focus(); fireEvent.click(enable)
  const dialog = screen.getByRole('dialog', { name: '브라우저에서 알림을 허용해 주세요' })
  expect(client.enable).not.toHaveBeenCalled()
  fireEvent.click(within(dialog).getByRole('button', { name: '확인했어요' }))
  expect(enable).toHaveFocus()
})

it('북마크에 없는 공고 알림도 최신 상태를 조회하고 접수 상태와 일정을 표시한다', async () => {
  const { refreshStatus } = setup()
  const alarms = within(await screen.findByRole('region', { name: '알림 관리' }))
  fireEvent.click(await alarms.findByRole('tab', { name: '공고 1' }))
  expect(refreshStatus).toHaveBeenCalledOnce()
  expect(alarms.getByText('접수중')).toBeVisible()
  expect(alarms.getByText('접수 10.20 ~ 10.30')).toBeVisible()
  expect(alarms.getByRole('button', { name: /접수중\s*공고/ })).toBeEnabled()
})
