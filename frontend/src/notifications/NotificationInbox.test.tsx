import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { expect, it, vi } from 'vitest'
import { NotificationInbox } from './NotificationInbox'
import { NotificationInboxContext, type InboxContextValue } from './NotificationInboxContext'
import type { NotificationItem, NotificationPageResult, NotificationRepository } from './notificationRepository'

const item: NotificationItem = { id: '10', type: 'ANNOUNCEMENT_CREATED', announcementId: '23', title: '서울 새 공고', createdAt: '2026-10-13T00:00:00Z', readAt: null, targetAvailable: true }
function setup(overrides: Partial<NotificationRepository> = {}) {
  const repository = { count: vi.fn(async () => 1), list: vi.fn(async () => ({ items: [item], nextCursor: null, readThrough: '10' })), read: vi.fn(async () => {}), readAll: vi.fn(async () => {}), ...overrides }
  const context: InboxContextValue = { userId: '1', unreadCount: 1, error: null, repository, revision: 0, invalidate: vi.fn(), sessionExpired: vi.fn() }
  function Location() { const location = useLocation(); return <output aria-label="현재 주소">{location.pathname}{location.search}</output> }
  const view = render(<MemoryRouter><NotificationInboxContext.Provider value={context}><NotificationInbox /><Location /></NotificationInboxContext.Provider></MemoryRouter>)
  return { ...view, repository, context }
}
it('열면 목록만 조회하고 항목을 눌러야 읽음 처리 후 상세로 이동한다', async () => {
  const { repository } = setup()
  const button = await screen.findByRole('button', { name: /서울 새 공고/ })
  expect(repository.read).not.toHaveBeenCalled()
  fireEvent.click(button)
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore?announcementId=23'))
  expect(repository.read).toHaveBeenCalledWith('10', expect.any(AbortSignal))
  expect(repository.readAll).not.toHaveBeenCalled()
})
it('읽음 실패는 오류와 별도 공고 이동을 제공하고 읽음으로 표시하지 않는다', async () => {
  setup({ read: vi.fn(async () => { throw new Error('network') }) })
  fireEvent.click(await screen.findByRole('button', { name: /서울 새 공고/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('읽음 처리하지 못했어요')
  expect(screen.getByRole('link', { name: '공고만 보기' })).toHaveAttribute('href', '/explore?announcementId=23')
  expect(screen.getByText('읽지 않음')).toBeVisible()
})
it('더 보기로 받은 항목과 삭제된 공고를 표시하고 모두 읽음은 최초 목록 경계만 사용한다', async () => {
  const list = vi.fn().mockResolvedValueOnce({ items: [item], nextCursor: 'page2', readThrough: '10' }).mockResolvedValue({ items: [{ ...item, id: '9', title: '삭제된 공고', targetAvailable: false }], nextCursor: null, readThrough: '15' })
  const { repository } = setup({ list })
  fireEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  expect(await screen.findByText('현재 확인할 수 없는 공고예요.')).toBeVisible()
  expect(repository.read).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '모두 읽음' }))
  await waitFor(() => expect(repository.readAll).toHaveBeenCalledWith('10', expect.any(AbortSignal)))
})
it('계정이 바뀌면 이전 목록 응답을 표시하지 않는다', async () => {
  let resolve!: (page: NotificationPageResult) => void
  const list = vi.fn().mockImplementationOnce(() => new Promise<NotificationPageResult>(done => { resolve = done })).mockResolvedValue({ items: [], nextCursor: null, readThrough: '0' })
  const { rerender, context } = setup({ list })
  rerender(<MemoryRouter><NotificationInboxContext.Provider value={{ ...context, userId: '2' }}><NotificationInbox /></NotificationInboxContext.Provider></MemoryRouter>)
  await screen.findByText('아직 받은 알림이 없어요.')
  await act(async () => { resolve({ items: [item], nextCursor: null, readThrough: '10' }) })
  expect(screen.queryByText('서울 새 공고')).not.toBeInTheDocument()
})
