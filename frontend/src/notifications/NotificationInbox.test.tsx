import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { expect, it, vi } from 'vitest'
import { NotificationInbox } from './NotificationInbox'
import { NotificationInboxContext, type InboxContextValue } from './NotificationInboxContext'
import type { NotificationItem, NotificationPageResult, NotificationRepository } from './notificationRepository'

const item: NotificationItem = { id: '10', type: 'ANNOUNCEMENT_CREATED', announcementId: '23', title: '서울 새 공고', createdAt: '2026-10-13T00:00:00Z', readAt: null, targetAvailable: true }
function setup(overrides: Partial<NotificationRepository> = {}, mypageReturn?: string) {
  const repository = { count: vi.fn(async () => 1), list: vi.fn(async () => ({ items: [item], nextCursor: null, readThrough: '10', totalCount: 1 })), read: vi.fn(async () => {}), readAll: vi.fn(async () => {}), delete: vi.fn(async () => {}), ...overrides }
  const context: InboxContextValue = { userId: '1', unreadCount: 1, error: null, repository, revision: 0, invalidate: vi.fn(), sessionExpired: vi.fn() }
  function Location() { const location = useLocation(); return <output aria-label="현재 주소" data-mypage-return={location.state?.mypageReturn}>{location.pathname}{location.search}</output> }
  const view = render(<MemoryRouter initialEntries={[{ pathname: "/notifications", state: { mypageReturn } }]}><NotificationInboxContext.Provider value={context}><NotificationInbox /><Location /></NotificationInboxContext.Provider></MemoryRouter>)
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
  const list = vi.fn().mockResolvedValueOnce({ items: [item], nextCursor: 'page2', readThrough: '10', totalCount: 1 }).mockResolvedValue({ items: [{ ...item, id: '9', title: '삭제된 공고', targetAvailable: false }], nextCursor: null, readThrough: '15', totalCount: 2 })
  const { repository } = setup({ list })
  fireEvent.click(await screen.findByRole('button', { name: '더 보기' }))
  expect(await screen.findByText('현재 확인할 수 없는 공고예요.')).toBeVisible()
  expect(repository.read).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '모두 읽음' }))
  await waitFor(() => expect(repository.readAll).toHaveBeenCalledWith('10', expect.any(AbortSignal)))
})
it('계정이 바뀌면 이전 목록 응답을 표시하지 않는다', async () => {
  let resolve!: (page: NotificationPageResult) => void
  const list = vi.fn().mockImplementationOnce(() => new Promise<NotificationPageResult>(done => { resolve = done })).mockResolvedValue({ items: [], nextCursor: null, readThrough: '0', totalCount: 0 })
  const { rerender, context } = setup({ list })
  rerender(<MemoryRouter><NotificationInboxContext.Provider value={{ ...context, userId: '2' }}><NotificationInbox /></NotificationInboxContext.Provider></MemoryRouter>)
  await screen.findByText('아직 받은 알림이 없어요.')
  await act(async () => { resolve({ items: [item], nextCursor: null, readThrough: '10', totalCount: 1 }) })
  expect(screen.queryByText('서울 새 공고')).not.toBeInTheDocument()
})

it('편집에서 선택한 알림만 확인 후 삭제하고 실패하면 선택을 보존한다', async () => {
  const remove = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue(undefined)
  const { repository } = setup({ delete: remove })
  await screen.findByRole('button', { name: /서울 새 공고/ })
  fireEvent.click(screen.getByRole('button', { name: '편집' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '서울 새 공고 선택' }))
  expect(repository.read).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '선택 삭제' }))
  let dialog = screen.getByRole('dialog', { name: '알림 1개를 삭제할까요?' })
  fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))
  expect(await within(dialog).findByRole('alert')).toHaveTextContent('삭제하지 못했어요')
  expect(screen.getByRole('checkbox', { name: '서울 새 공고 선택' })).toBeChecked()
  fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(remove).toHaveBeenLastCalledWith({ ids: ['10'] }, expect.any(AbortSignal))
})
it('전체 선택은 모든 페이지를 포함하고 편집 중 갱신되어도 최초 경계와 선택 제외를 보존한다', async () => {
  const { context, rerender, repository } = setup({ list: vi.fn(async () => ({ items: [item], nextCursor: 'more', readThrough: '10', totalCount: 35 })) })
  await screen.findByRole('button', { name: /서울 새 공고/ })
  fireEvent.click(screen.getByRole('button', { name: '편집' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '서울 새 공고 선택' }))
  rerender(<MemoryRouter><NotificationInboxContext.Provider value={{ ...context, revision: 1, unreadCount: 36 }}><NotificationInbox /></NotificationInboxContext.Provider></MemoryRouter>)
  expect(repository.list).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '선택 삭제' }))
  const dialog = screen.getByRole('dialog', { name: '알림 34개를 삭제할까요?' })
  fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))
  await waitFor(() => expect(repository.delete).toHaveBeenCalledWith({ deleteThrough: '10', excludedIds: ['10'] }, expect.any(AbortSignal)))
})
it('계정이 바뀌면 이전 계정의 선택과 삭제 확인을 지운다', async () => {
  const { context, rerender, repository } = setup()
  await screen.findByRole('button', { name: /서울 새 공고/ })
  fireEvent.click(screen.getByRole('button', { name: '편집' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(screen.getByRole('button', { name: '선택 삭제' }))
  rerender(<MemoryRouter><NotificationInboxContext.Provider value={{ ...context, userId: '2' }}><NotificationInbox /></NotificationInboxContext.Provider></MemoryRouter>)
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(repository.delete).not.toHaveBeenCalled()
})

it('일정 알림은 일정명과 기간·대상·조건·원문을 분리해 표시한다', async () => {
  const schedule = { kind: 'DOCUMENT_SUBMISSION' as const, name: '예비 입주자 서류 제출', startDate: '2026-10-14', endDate: '2026-10-15', startTime: '10:00:00', endTime: '17:00:00', scope: '경희궁자이 · 1순위', condition: '서류 제출 대상자', sourceUrl: 'https://example.com/notice', sourcePage: 3, phase: 'START' as const }
  setup({ list: vi.fn(async () => ({ items: [{ ...item, type: 'SCHEDULE_REMINDER' as const, content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '23', targetName: schedule.name, message: '내일 서류 제출 일정이 시작돼요.', schedule } }], nextCursor: null, readThrough: '10', totalCount: 1 })) })
  expect(await screen.findByText('예비 입주자 서류 제출')).toBeVisible()
  expect(screen.getByText('2026. 10. 14. 10:00 – 2026. 10. 15. 17:00')).toBeVisible()
  expect(screen.getByText('경희궁자이 · 1순위')).toBeVisible()
  expect(screen.getByText('서류 제출 대상자')).toBeVisible()
  const source = screen.getByRole('link', { name: '원문 확인 · 3쪽' })
  expect(source).toHaveAttribute('href', 'https://example.com/notice')
  expect(source.closest('button')).toBeNull()
  expect(screen.queryByText('내일 서류 제출 일정이 시작돼요.')).not.toBeInTheDocument()
})

it.each([false, true])('마이페이지 보관함에서 공고 이동 시 복귀 탭을 유지한다 (읽음 실패: %s)', async (failure) => {
  setup(failure ? { read: vi.fn().mockRejectedValue(new Error('offline')) } : {}, '/mypage/notifications')
  fireEvent.click(await screen.findByRole('button', { name: /서울 새 공고/ }))
  if (failure) fireEvent.click(await screen.findByRole('link', { name: '공고만 보기' }))
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore?announcementId=23'))
  expect(screen.getByLabelText('현재 주소')).toHaveAttribute('data-mypage-return', '/mypage/notifications')
})
