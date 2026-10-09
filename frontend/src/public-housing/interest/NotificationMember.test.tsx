import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { NotificationInterestButton, NotificationInterestProvider } from './NotificationInterest'

it('회원은 이메일 없이 저장하고 준비 중 안내를 확인한다', async () => {
  const repository = { record: vi.fn().mockResolvedValue(undefined), loadStatus: vi.fn().mockResolvedValue({ emailConfirmed: false, targets: [] }) }
  render(<MemoryRouter><NotificationInterestProvider repository={repository}>
    <NotificationInterestButton target={{ type: 'COMPLEX', id: '1', name: '서울 단지' }} source="COMPLEX_DETAIL" />
  </NotificationInterestProvider></MemoryRouter>)
  const button = screen.getByRole('button', { name: '서울 단지 알림 받기' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(await screen.findByRole('dialog', { name: '알림 기능을 준비하고 있어요' })).toBeVisible()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(repository.record).toHaveBeenCalledWith(expect.objectContaining({ eventType: 'CONFIRMED' }))
  expect(button).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '확인' }))
  expect(button).toHaveFocus()
})

it('비회원은 설정을 저장하지 않고 로그인 모달로 이동한다', async () => {
  const repository = { record: vi.fn(), loadStatus: vi.fn().mockResolvedValue({ guest: true, emailConfirmed: false, targets: [] }) }
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
