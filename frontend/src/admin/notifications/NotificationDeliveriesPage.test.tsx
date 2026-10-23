import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { NotificationDeliveriesPage } from './NotificationDeliveriesPage'
import { listDeliveries } from './deliveryApi'
vi.mock('./deliveryApi', async original => ({ ...await original<typeof import('./deliveryApi')>(), listDeliveries: vi.fn() }))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(listDeliveries).mockResolvedValue({ page: 0, totalElements: 1, totalPages: 1, items: [{ notificationId: 1, userId: 7, announcementId: 21, title: '한스빌 모집', deliveryStatus: 'SENT', deliveryId: 2, attempts: 1, errorCode: null, createdAt: '2026-10-19T00:00:00Z', nextAttemptAt: null, finishedAt: '2026-10-19T00:01:00Z', readAt: null, deleted: false, expiresAt: '2027-01-19T00:00:00Z' }] })
})
it('푸시 제공자 접수와 열람을 구분하고 회원 맥락을 검색 후에도 유지한다', async () => {
  render(<MemoryRouter initialEntries={['/admin/notification-deliveries?userId=7']}><NotificationDeliveriesPage /></MemoryRouter>)
  expect(await screen.findByText('푸시 제공자 접수', { selector: 'strong' })).toBeVisible()
  expect(screen.getByText('미열람')).toBeVisible()
  fireEvent.change(screen.getByLabelText('발송 상태'), { target: { value: 'FAILED' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(listDeliveries).toHaveBeenCalledTimes(2))
  expect(vi.mocked(listDeliveries).mock.calls[1][0].get('userId')).toBe('7')
  expect(vi.mocked(listDeliveries).mock.calls[1][0].get('status')).toBe('FAILED')
})
