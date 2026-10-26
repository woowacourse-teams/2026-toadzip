import { createRef } from 'react'
import { MemberMenuModal } from '../../user/auth/MemberMenuModal'
import { NotificationRequestError, type NotificationInterestRepository } from './notificationInterestRepository'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'

it('회원은 이메일 없이 저장하고 신청 완료 안내를 확인한다', async () => {
  const repository = { record: vi.fn<NotificationInterestRepository['record']>().mockImplementation(async event => ({ ...event, outcome: 'ACTIVATED', occurredAt: '2026-10-09T00:00:00Z', settingsRevision: 1, currentTarget: { active: true, expiresAt: '2027-04-07T00:00:00Z', noticeVersion: 'notification-2026-10-09-v1', requestedAt: '2026-10-09T00:00:00Z' } })), loadStatus: vi.fn().mockResolvedValue({ userId: '1', settingsRevision: 0, targets: [] }) }
  render(<MemoryRouter><NotificationInterestProvider repository={repository}>
    <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
  </NotificationInterestProvider></MemoryRouter>)
  const button = screen.getByRole('button', { name: '서울 단지 알림 받기' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(repository.record).not.toHaveBeenCalled()
  expect(screen.getByRole('dialog')).not.toHaveTextContent('이용자 ID와 선택한 대상')
  expect(within(screen.getByRole('dialog')).getByRole('link', { name: '개인정보처리방침' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '신청하기' }))
  expect(await screen.findByRole('dialog', { name: '알림 신청을 완료했어요' })).toBeVisible()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(repository.record).toHaveBeenCalledWith(expect.objectContaining({ eventType: 'CONFIRMED' }))
  expect(button).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '확인' }))
  expect(button).toHaveFocus()
})

it('비회원은 설정을 저장하지 않고 로그인 모달로 이동한다', async () => {
  const repository = { record: vi.fn(), loadStatus: vi.fn().mockResolvedValue({ guest: true, targets: [] }) }
  render(<MemoryRouter><NotificationInterestProvider repository={repository}>
    <NotificationInterestButton target={{ type: 'REGION', id: '11', name: '서울' }} source="REGION_SEARCH" />
  </NotificationInterestProvider></MemoryRouter>)
  const button = screen.getByRole('button', { name: '서울 알림 받기' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(await screen.findByRole('dialog')).toHaveTextContent('로그인한 사용자만 이용할 수 있어요.')
  expect(repository.record).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '로그인' }))
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
})

vi.mock('../../privacy/usePrivacy', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../privacy/usePrivacy')>()
  const notices = [{ key: 'PRIVACY_POLICY', version: 'privacy-2026-10-09-v1' }, { key: 'NOTIFICATION_NOTICE', version: 'notification-2026-10-09-v1' }]
  return { ...original, usePrivacyNotices: () => ({ notices, error: false, retry: vi.fn() }) }
})


it('모달에서 알림을 해제하면 완료 안내도 모달 내부에 표시한다', async () => {
  const repository: NotificationInterestRepository = {
    record: vi.fn(async event => ({ ...event, outcome: 'CANCELLED' as const, occurredAt: '2026-10-15T00:00:00Z', settingsRevision: 1, currentTarget: { active: false, expiresAt: null, noticeVersion: null, requestedAt: null } })),
    loadStatus: vi.fn().mockResolvedValue({ userId: '1', settingsRevision: 0, targets: [{ targetType: 'COMPLEX', targetId: '1', targetName: '서울 단지' }] }),
  }
  render(<MemoryRouter><NotificationInterestProvider repository={repository}>
    <MemberMenuModal view="settings" onClose={vi.fn()} returnFocusRef={createRef<HTMLButtonElement>()} />
  </NotificationInterestProvider></MemoryRouter>)
  const dialog = screen.getByRole('dialog', { name: '알림 보관함' })
  fireEvent.click(await within(dialog).findByRole('button', { name: '서울 단지 알림 해제' }))
  await waitFor(() => expect(within(dialog).queryByText('서울 단지')).not.toBeInTheDocument())
  expect(await within(dialog).findByRole('status')).toHaveTextContent('해제했어요')
})

it('배포 후 예전 안내로 신청이 거절되면 같은 요청을 반복하지 않고 새로고침을 안내한다', async () => {
  const repository: NotificationInterestRepository = {
    record: vi.fn().mockRejectedValue(new NotificationRequestError(400)),
    loadStatus: vi.fn().mockResolvedValue({ userId: '1', settingsRevision: 0, targets: [] }),
  }
  render(<MemoryRouter><NotificationInterestProvider repository={repository}>
    <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
  </NotificationInterestProvider></MemoryRouter>)
  const subscribe = screen.getByRole('button', { name: '서울 단지 알림 받기' })
  await waitFor(() => expect(subscribe).toBeEnabled())
  fireEvent.click(subscribe)
  fireEvent.click(screen.getByRole('button', { name: '신청하기' }))
  const error = await screen.findByRole('dialog', { name: '알림 요청 오류' })
  expect(within(error).getByRole('alert')).toHaveTextContent('새로고침')
  expect(within(error).queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  const reload = vi.fn()
  vi.stubGlobal('location', { ...window.location, reload })
  try { fireEvent.click(within(error).getByRole('button', { name: '새로고침' })); expect(reload).toHaveBeenCalledOnce() }
  finally { vi.unstubAllGlobals() }
  expect(repository.record).toHaveBeenCalledOnce()
})
