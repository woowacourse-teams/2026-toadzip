import { captureProductEvent } from '../../analytics/productAnalytics'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { GuestCancellationPage } from './GuestCancellationPage'
import { requestGuestCancellation, verifyGuestCancellation } from './guestCancellationApi'

vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(), createAnalyticsId: () => 'test-action' }))

vi.mock('./guestCancellationApi', () => ({
  requestGuestCancellation: vi.fn(),
  verifyGuestCancellation: vi.fn(),
}))

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(requestGuestCancellation).mockReset().mockResolvedValue(undefined)
  vi.mocked(verifyGuestCancellation).mockReset().mockResolvedValue(undefined)
})

describe('브라우저 데이터를 지운 사용자의 취소', () => {
  it('신청 주소로 요청하고 수동으로 받은 코드로 취소한다', async () => {
    render(<MemoryRouter><GuestCancellationPage /></MemoryRouter>)
    fireEvent.change(screen.getByRole('textbox', { name: '신청한 이메일' }), { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '취소 요청하기' }))
    await waitFor(() => expect(requestGuestCancellation).toHaveBeenCalledWith('guest@example.com'))
    fireEvent.change(await screen.findByRole('textbox', { name: '이메일로 받은 확인 코드' }),
      { target: { value: 'manual-code' } })
    fireEvent.click(screen.getByRole('button', { name: '비로그인 알림 모두 취소' }))
    await waitFor(() => expect(verifyGuestCancellation).toHaveBeenCalledWith('guest@example.com', 'manual-code'))
    expect(await screen.findByRole('status')).toHaveTextContent('비로그인 알림을 모두 취소')
    expect(captureProductEvent).toHaveBeenCalledWith('guest_cancellation_request_accepted', expect.any(Object))
    expect(captureProductEvent).toHaveBeenCalledWith('guest_bulk_cancellation_completed', expect.any(Object))
    expect(JSON.stringify(vi.mocked(captureProductEvent).mock.calls)).not.toMatch(/guest@example|manual-code/)
  })

  it('잘못된 코드에는 완료 상태를 보이지 않는다', async () => {
    vi.mocked(verifyGuestCancellation).mockRejectedValue(new Error('이메일과 확인 코드를 다시 확인해 주세요.'))
    render(<MemoryRouter><GuestCancellationPage /></MemoryRouter>)
    fireEvent.change(screen.getByRole('textbox', { name: '신청한 이메일' }), { target: { value: 'guest@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '취소 요청하기' }))
    fireEvent.change(await screen.findByRole('textbox', { name: '이메일로 받은 확인 코드' }),
      { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: '비로그인 알림 모두 취소' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('다시 확인해 주세요')
    expect(captureProductEvent).toHaveBeenCalledWith('guest_cancellation_verification_failed', expect.any(Object))
    expect(vi.mocked(captureProductEvent).mock.calls.some(([name]) => name === 'guest_bulk_cancellation_completed')).toBe(false)
  })
})
