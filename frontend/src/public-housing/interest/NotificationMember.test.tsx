import type { NotificationInterestRepository } from './notificationInterestRepository'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'

it('회원은 이메일 없이 저장하고 준비 중 안내를 확인한다', async () => {
  const repository = { record: vi.fn<NotificationInterestRepository['record']>().mockImplementation(async event => ({ ...event, outcome: 'ACTIVATED', occurredAt: '2026-10-09T00:00:00Z', settingsRevision: 1, currentTarget: { active: true, expiresAt: '2027-04-07T00:00:00Z', noticeVersion: 'notification-2026-10-09-v1', requestedAt: '2026-10-09T00:00:00Z' } })), loadStatus: vi.fn().mockResolvedValue({ userId: '1', settingsRevision: 0, targets: [] }) }
  render(<MemoryRouter><NotificationInterestProvider repository={repository}>
    <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
  </NotificationInterestProvider></MemoryRouter>)
  const button = screen.getByRole('button', { name: '서울 단지 알림 받기' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(repository.record).not.toHaveBeenCalled()
  expect(screen.getByRole('dialog')).toHaveTextContent('알림 설정 저장에 이용자 ID')
  fireEvent.click(screen.getByRole('button', { name: '신청하기' }))
  expect(await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })).toBeVisible()
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
