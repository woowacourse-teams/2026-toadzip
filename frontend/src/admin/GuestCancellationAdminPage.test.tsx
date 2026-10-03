import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { GuestCancellationAdminPage } from './GuestCancellationAdminPage'
import { issueGuestCancellationCode, loadGuestCancellationRequests, markGuestCancellationCodeSent, reissueGuestCancellationCode } from '../public-housing/interest/guestCancellationApi'

vi.mock('../public-housing/interest/guestCancellationApi', () => ({
  loadGuestCancellationRequests: vi.fn(),
  issueGuestCancellationCode: vi.fn(),
  markGuestCancellationCodeSent: vi.fn(),
  reissueGuestCancellationCode: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(loadGuestCancellationRequests).mockReset().mockResolvedValue([{
    id: 'request-1', email: 'guest@example.com', requestedAt: '2026-09-29T05:00:00Z',
    codeExpiresAt: null, failedAttempts: 0, codeSentAt: null, codeSentBy: null,
  }])
  vi.mocked(issueGuestCancellationCode).mockReset().mockResolvedValue({
    code: 'manual-code', expiresAt: '2026-09-30T05:00:00Z',
  })
  vi.mocked(markGuestCancellationCodeSent).mockReset().mockResolvedValue(undefined)
  vi.mocked(reissueGuestCancellationCode).mockReset().mockResolvedValue({
    code: 'replacement-code', expiresAt: '2099-10-01T05:00:00Z',
  })
})

it('발급된 코드를 잃어버려도 확인 후 즉시 다시 만들 수 있다', async () => {
  vi.mocked(loadGuestCancellationRequests).mockResolvedValue([{
    id: 'request-1', email: 'guest@example.com', requestedAt: '2026-09-29T05:00:00Z',
    codeExpiresAt: '2099-10-01T05:00:00Z', failedAttempts: 0,
    codeSentAt: '2026-09-29T06:00:00Z', codeSentBy: 'admin',
  }])
  render(<GuestCancellationAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '코드 다시 만들기' }))
  expect(screen.getByText('새 코드를 만들면 기존 코드는 사용할 수 없습니다. 다시 만들어 발송할까요?')).toBeVisible()
  expect(reissueGuestCancellationCode).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '돌아가기' }))
  expect(reissueGuestCancellationCode).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '코드 다시 만들기' }))
  fireEvent.click(screen.getByRole('button', { name: '기존 코드 무효화하고 다시 만들기' }))
  await waitFor(() => expect(reissueGuestCancellationCode).toHaveBeenCalledWith('request-1'))
  expect(await screen.findByDisplayValue('replacement-code')).toBeVisible()
})

it('실제 이메일 발송 후에만 발송 완료를 기록한다', async () => {
  render(<GuestCancellationAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '확인 코드 만들기' }))
  expect(await screen.findByDisplayValue('manual-code')).toBeVisible()
  expect(markGuestCancellationCodeSent).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '이메일 발송 완료 기록' }))
  await waitFor(() => expect(markGuestCancellationCodeSent).toHaveBeenCalledWith('request-1', 'manual-code'))
})

it('관리자가 취소 요청을 보고 수동 발송할 코드를 한 번 발급한다', async () => {
  render(<GuestCancellationAdminPage />)
  expect(await screen.findByText('guest@example.com')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '확인 코드 만들기' }))
  await waitFor(() => expect(issueGuestCancellationCode).toHaveBeenCalledWith('request-1'))
  expect(await screen.findByDisplayValue('manual-code')).toBeVisible()
  expect(screen.getByText('한 번만 표시되는 코드 · 신청 주소로 수동 발송')).toBeVisible()
})

it('조회 중에는 로딩을 표시하고 성공한 빈 목록에서만 대기 요청 없음을 표시한다', async () => {
  let resolve!: (value: []) => void
  vi.mocked(loadGuestCancellationRequests).mockReturnValue(new Promise<[]>(yes => { resolve = yes }))
  render(<GuestCancellationAdminPage />)
  expect(screen.getByRole('status')).toHaveTextContent('목록을 불러오는 중')
  expect(screen.queryByText('대기 중인 요청이 없습니다.')).not.toBeInTheDocument()
  await act(async () => resolve([]))
  expect(screen.getByText('대기 중인 요청이 없습니다.')).toBeVisible()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('조회 실패를 빈 목록으로 표시하지 않으며 새로고침으로 회복한다', async () => {
  vi.mocked(loadGuestCancellationRequests).mockRejectedValueOnce(new Error('조회 실패')).mockResolvedValue([])
  render(<GuestCancellationAdminPage />)
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패')
  expect(screen.queryByText('대기 중인 요청이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '목록 새로고침' }))
  expect(await screen.findByText('대기 중인 요청이 없습니다.')).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('StrictMode의 이전 목록 응답이 뒤늦게 실패해도 최신 목록에 오류를 붙이지 않는다', async () => {
  let reject!: (error: Error) => void
  vi.mocked(loadGuestCancellationRequests).mockReturnValueOnce(new Promise<never>((_yes, no) => { reject = no }))
  render(<StrictMode><GuestCancellationAdminPage /></StrictMode>)
  expect(await screen.findByText('guest@example.com')).toBeVisible()
  await act(async () => reject(new Error('오래된 실패')))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('겹친 새로고침 중 오래된 빈 응답은 최신 요청 목록을 지우지 않는다', async () => {
  let resolve!: (value: []) => void
  vi.mocked(loadGuestCancellationRequests).mockReturnValueOnce(new Promise<[]>(yes => { resolve = yes }))
  render(<GuestCancellationAdminPage />)
  fireEvent.click(screen.getByRole('button', { name: '목록 새로고침' }))
  expect(await screen.findByText('guest@example.com')).toBeVisible()
  await act(async () => resolve([]))
  expect(screen.getByText('guest@example.com')).toBeVisible()
  expect(screen.queryByText('대기 중인 요청이 없습니다.')).not.toBeInTheDocument()
})
