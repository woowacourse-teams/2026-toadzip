import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
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
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/announcements/23'))
  expect(repository.read).toHaveBeenCalledWith('10', expect.any(AbortSignal))
  expect(repository.readAll).not.toHaveBeenCalled()
})

it('출처별 보관함 조회와 알림 열기만 집계하고 회원·알림 식별값을 보내지 않는다', async () => {
  vi.stubEnv('MODE', 'development')
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken')
  vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
  vi.stubEnv('VITE_ANALYTICS_ENV', 'dev')
  vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true')
  const network = vi.fn().mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', network)
  localStorage.clear(); sessionStorage.clear()
  history.replaceState({}, '', '/notifications?utm_source=community_a')
  const attribution = await import('../analytics/campaignAttribution')
  attribution.consumeCampaignEntry()
  try {
    setup()
    const button = await screen.findByRole('button', { name: /서울 새 공고/ })
    await waitFor(() => expect(network).toHaveBeenCalledOnce())
    fireEvent.click(button)
    await waitFor(() => expect(network).toHaveBeenCalledTimes(2))
    const properties = network.mock.calls.map(([, options]) => JSON.parse(options.body).properties)
    expect(properties.map(value => value.action)).toEqual(['notification_inbox_viewed', 'notification_opened'])
    expect(JSON.stringify(properties)).not.toMatch(/user_id|notification_id|announcement_id|서울|title|readThrough/)
  } finally {
    sessionStorage.clear(); attribution.campaignEntry(); vi.unstubAllEnvs(); vi.unstubAllGlobals()
  }
})
it('읽음 실패는 오류와 별도 공고 이동을 제공하고 읽음으로 표시하지 않는다', async () => {
  setup({ read: vi.fn(async () => { throw new Error('network') }) })
  fireEvent.click(await screen.findByRole('button', { name: /서울 새 공고/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('읽음 처리하지 못했어요')
  expect(screen.getByRole('link', { name: '공고만 보기' })).toHaveAttribute('href', '/announcements/23')
  expect(screen.getByText('읽지 않음')).toBeVisible()
})
it('더 보기로 받은 항목과 삭제된 공고를 표시하고 모두 읽음은 최초 목록 경계만 사용한다', async () => {
  const list = vi.fn().mockResolvedValueOnce({ items: [item], nextCursor: 'page2', readThrough: '10', totalCount: 1 }).mockResolvedValue({ items: [{ ...item, id: '9', announcementId: '24', title: '삭제된 공고', targetAvailable: false }], nextCursor: null, readThrough: '15', totalCount: 2 })
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
  fireEvent.click(screen.getByRole('button', { name: '삭제' }))
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
  expect(screen.getByRole('button', { name: '전체 삭제' })).toBeEnabled()
  fireEvent.click(screen.getByRole('checkbox', { name: '서울 새 공고 선택' }))
  rerender(<MemoryRouter><NotificationInboxContext.Provider value={{ ...context, revision: 1, unreadCount: 36 }}><NotificationInbox /></NotificationInboxContext.Provider></MemoryRouter>)
  expect(repository.list).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: '삭제' }))
  const dialog = screen.getByRole('dialog', { name: '알림 34개를 삭제할까요?' })
  fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))
  await waitFor(() => expect(repository.delete).toHaveBeenCalledWith({ deleteThrough: '10', excludedIds: ['10'] }, expect.any(AbortSignal)))
})
it('계정이 바뀌면 이전 계정의 선택과 삭제 확인을 지운다', async () => {
  const { context, rerender, repository } = setup()
  await screen.findByRole('button', { name: /서울 새 공고/ })
  fireEvent.click(screen.getByRole('button', { name: '편집' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(screen.getByRole('button', { name: '전체 삭제' }))
  rerender(<MemoryRouter><NotificationInboxContext.Provider value={{ ...context, userId: '2' }}><NotificationInbox /></NotificationInboxContext.Provider></MemoryRouter>)
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(repository.delete).not.toHaveBeenCalled()
})

it('일정 알림은 일정명과 기간·대상·조건·원문을 분리해 표시한다', async () => {
  const schedule = { kind: 'DOCUMENT_SUBMISSION' as const, name: '예비 입주자 서류 제출', startDate: '2026-10-14', endDate: '2026-10-15', startTime: '10:00:00', endTime: '17:00:00', scope: '경희궁자이 · 1순위', condition: '서류 제출 대상자', sourceUrl: 'https://example.com/notice', sourcePage: 3, phase: 'START' as const }
  setup({ list: vi.fn(async () => ({ items: [{ ...item, type: 'SCHEDULE_REMINDER' as const, content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '23', targetName: schedule.name, message: '내일 서류 제출 일정이 시작돼요.', schedule } }], nextCursor: null, readThrough: '10', totalCount: 1 })) })
  expect(await screen.findByText('예비 입주자 서류 제출')).toBeVisible()
  expect(screen.getByText('2026년 10월 14일(수) 10:00 – 10월 15일(목) 17:00')).toBeVisible()
  expect(screen.getByText('경희궁자이 · 1순위')).toBeVisible()
  expect(screen.getByText('서류 제출 대상자')).toBeVisible()
  expect(screen.queryByRole('link', { name: /원문 확인/ })).not.toBeInTheDocument()
  const open = screen.getByRole('button', { name: '공고 보기' })
  expect(open.parentElement).toHaveTextContent(/^예비 입주자 서류 제출공고 보기/)
  expect(screen.queryByText('내일 서류 제출 일정이 시작돼요.')).not.toBeInTheDocument()
})

it('같은 날 일정은 날짜를 한 번만 쓰고 시간이 미정이면 원문 확인을 안내한다', async () => {
  const schedule = { kind: 'WINNER_ANNOUNCEMENT' as const, name: '당첨자 발표', startDate: '2026-12-04', endDate: '2026-12-04', startTime: '14:00:00', endTime: null, scope: null, condition: null, sourceUrl: null, sourcePage: null, phase: 'START_END' as const }
  setup({ list: vi.fn(async () => ({ items: [{ ...item, type: 'SCHEDULE_REMINDER' as const, content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '23', targetName: schedule.name, message: '', schedule } }], nextCursor: null, readThrough: '10', totalCount: 1 })) })
  expect(await screen.findByText('2026년 12월 4일(금) 14:00')).toBeVisible()
  expect(screen.getByText('한국 시간 · 미정 시간은 원문에서 확인')).toBeVisible()
  expect(screen.queryByRole('link', { name: /원문 확인/ })).not.toBeInTheDocument()
})

it.each([false, true])('마이페이지 보관함에서 공고 이동 시 복귀 탭을 유지한다 (읽음 실패: %s)', async (failure) => {
  setup(failure ? { read: vi.fn().mockRejectedValue(new Error('offline')) } : {}, '/mypage/notifications')
  fireEvent.click(await screen.findByRole('button', { name: /서울 새 공고/ }))
  if (failure) fireEvent.click(await screen.findByRole('link', { name: '공고만 보기' }))
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/announcements/23'))
  expect(screen.getByLabelText('현재 주소')).toHaveAttribute('data-mypage-return', '/mypage/notifications')
})

it('공고 보기는 알림을 읽음 처리하고 앱의 공고 상세로 이동한다', async () => {
  const schedule = { kind: 'DOCUMENT_SUBMISSION' as const, name: '서류제출', startDate: '2026-10-17', endDate: '2026-10-21', startTime: null, endTime: null, scope: null, condition: null, sourceUrl: 'https://example.com/notice', sourcePage: null, phase: 'START' as const }
  const { repository } = setup({ list: vi.fn(async () => ({ items: [{ ...item, type: 'SCHEDULE_REMINDER' as const, content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '23', targetName: schedule.name, message: '', schedule } }], nextCursor: null, readThrough: '10', totalCount: 1 })) })
  fireEvent.click(await screen.findByRole('button', { name: '공고 보기' }))
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/announcements/23'))
  expect(repository.read).toHaveBeenCalledWith('10', expect.any(AbortSignal))
})

it('확인할 수 없는 공고에는 공고 보기를 두지 않는다', async () => {
  const schedule = { kind: 'CONTRACT' as const, name: '계약', startDate: '2026-11-17', endDate: '2026-11-17', startTime: null, endTime: null, scope: null, condition: null, sourceUrl: null, sourcePage: null, phase: 'START_END' as const }
  setup({ list: vi.fn(async () => ({ items: [{ ...item, targetAvailable: false, type: 'SCHEDULE_REMINDER' as const, content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '23', targetName: schedule.name, message: '', schedule } }], nextCursor: null, readThrough: '10', totalCount: 1 })) })
  expect(await screen.findByText('계약')).toBeVisible()
  expect(screen.queryByRole('button', { name: '공고 보기' })).not.toBeInTheDocument()
})

describe('공고별 알림 묶음', () => {
  const docs = { kind: 'DOCUMENT_SUBMISSION' as const, name: '서류제출', startDate: '2026-10-17', endDate: '2026-10-21', startTime: null, endTime: null, scope: '춘천우두1단지', condition: null, sourceUrl: 'https://example.com/notice', sourcePage: null }
  const reminder = (id: string, phase: 'START' | 'END', createdAt: string, readAt: string | null): NotificationItem => ({
    id, type: 'SCHEDULE_REMINDER', announcementId: '77', title: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집', createdAt, readAt, targetAvailable: true,
    content: { type: 'SCHEDULE_REMINDER', targetType: 'ANNOUNCEMENT', targetId: '77', targetName: '서류제출', message: '', schedule: { ...docs, phase } },
  })
  const created: NotificationItem = { ...item, id: '11', announcementId: '77', content: { type: 'ANNOUNCEMENT_CREATED', targetType: 'REGION', targetId: '11', targetName: '서울', message: '서울 새 공고가 올라왔어요.', schedule: null }, title: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집', createdAt: '2026-10-10T00:00:00Z', readAt: '2026-10-10T01:00:00Z' }
  const other: NotificationItem = { ...item, id: '9', announcementId: '40', title: '서울 새 공고', createdAt: '2026-10-12T00:00:00Z' }
  const items = [reminder('13', 'END', '2026-10-20T00:00:00Z', null), reminder('12', 'START', '2026-10-16T00:00:00Z', null), other, created]

  it('같은 공고 알림은 최근 알림을 대표로 묶고 안 읽은 수를 보여 준다', async () => {
    setup({ list: vi.fn(async () => ({ items, nextCursor: null, readThrough: '13', totalCount: 4 })) })
    const toggle = await screen.findByRole('button', { name: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집 알림 3개 펼치기' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const group = toggle.closest('li')!
    expect(within(group).getByText('서류 제출 · 마감 안내')).toBeVisible()
    expect(within(group).getByText('안 읽음 2')).toBeVisible()
    expect(within(group).queryByText('서류 제출 · 시작 안내')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /서울 새 공고/ })).toBeVisible()
  })

  it('펼치면 그 공고 알림을 최신 알림부터 내용만 보여 주고 안 읽은 알림을 표시한다', async () => {
    setup({ list: vi.fn(async () => ({ items, nextCursor: null, readThrough: '13', totalCount: 4 })) })
    fireEvent.click(await screen.findByRole('button', { name: /알림 3개 펼치기/ }))
    const list = screen.getByRole('list', { name: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집 알림' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows.map((row) => row.getAttribute('data-notification-id'))).toEqual(['13', '12', '11'])
    expect(within(list).queryByText('서류 제출 · 시작 안내')).not.toBeInTheDocument()
    expect(within(list).queryByText('춘천시, 홍천군지역 국민임대주택 예비입주자 모집')).not.toBeInTheDocument()
    expect(within(rows[2]).getByText('서울 새 공고가 올라왔어요.')).toBeVisible()
    expect(within(rows[0]).getByText('서류제출')).toBeVisible()
    expect(within(rows[2]).queryByText('읽지 않음')).not.toBeInTheDocument()
    expect(within(rows[0]).getByText('읽지 않음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /알림 3개 접기/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('공고 보기는 그 공고의 안 읽은 알림을 모두 읽음 처리하고 상세로 이동한다', async () => {
    const { repository } = setup({ list: vi.fn(async () => ({ items, nextCursor: null, readThrough: '13', totalCount: 4 })) })
    fireEvent.click(await screen.findByRole('button', { name: /알림 3개 펼치기/ }))
    const list = screen.getByRole('list', { name: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집 알림' })
    fireEvent.click(within(within(list).getAllByRole('listitem')[2]).getByRole('button', { name: '공고 보기' }))
    await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/announcements/77'))
    expect(vi.mocked(repository.read).mock.calls.map(([id]) => id).sort()).toEqual(['11', '12', '13'])
  })

  it('묶음의 안 읽음과 알림 수 사이에 여백을 둔다', async () => {
    const { readFileSync } = await import('node:fs')
    const { resolve } = await import('node:path')
    const css = readFileSync(resolve(process.cwd(), 'src/notifications/NotificationInbox.module.css'), 'utf8')
    expect(css).toMatch(/\.groupMeta\s*\{[^}]*gap:\s*8px;/)
  })

  const groupBox = () => screen.getByRole('checkbox', { name: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집 알림 3개 선택' }) as HTMLInputElement
  function editGroups() {
    const view = setup({ list: vi.fn(async () => ({ items, nextCursor: null, readThrough: '13', totalCount: 4 })) })
    return view
  }

  it('편집할 때도 공고별 묶음을 유지하고 묶음 단위로 선택할 수 있다', async () => {
    const { repository } = editGroups()
    fireEvent.click(await screen.findByRole('button', { name: '편집' }))
    expect(screen.getByRole('button', { name: /알림 3개 펼치기/ })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('checkbox', { name: '서울 새 공고 선택' })).not.toBeChecked()
    fireEvent.click(groupBox())
    expect(groupBox()).toBeChecked()
    expect(screen.getByText('3개 선택')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: '알림 3개를 삭제할까요?' })).getByRole('button', { name: '삭제' }))
    await waitFor(() => expect(repository.delete).toHaveBeenCalled())
    const [request] = vi.mocked(repository.delete).mock.calls[0]
    expect('ids' in request && [...request.ids].sort()).toEqual(['11', '12', '13'])
  })

  it('편집 중 묶음을 펼쳐 알림을 하나씩 고르면 묶음 선택이 일부 선택으로 바뀐다', async () => {
    editGroups()
    fireEvent.click(await screen.findByRole('button', { name: '편집' }))
    fireEvent.click(screen.getByRole('button', { name: /알림 3개 펼치기/ }))
    const list = screen.getByRole('list', { name: '춘천시, 홍천군지역 국민임대주택 예비입주자 모집 알림' })
    expect(within(list).queryByRole('button', { name: '공고 보기' })).not.toBeInTheDocument()
    fireEvent.click(within(list).getByRole('checkbox', { name: '서류 제출 · 시작 안내 선택' }))
    expect(screen.getByText('1개 선택')).toBeVisible()
    expect(groupBox().checked).toBe(false)
    expect(groupBox().indeterminate).toBe(true)
    fireEvent.click(groupBox())
    expect(groupBox()).toBeChecked()
    expect(within(list).getByRole('checkbox', { name: '서울 새 공고가 올라왔어요. 선택' })).toBeChecked()
    fireEvent.click(groupBox())
    expect(screen.getByText('0개 선택')).toBeVisible()
    expect(within(list).getByRole('checkbox', { name: '서류 제출 · 마감 안내 선택' })).not.toBeChecked()
  })

  it('전체 선택 뒤 묶음을 해제하면 그 공고 알림만 삭제에서 뺀다', async () => {
    const { repository } = editGroups()
    fireEvent.click(await screen.findByRole('button', { name: '편집' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }))
    expect(groupBox()).toBeChecked()
    fireEvent.click(groupBox())
    expect(screen.getByText('1개 선택')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: '알림 1개를 삭제할까요?' })).getByRole('button', { name: '삭제' }))
    await waitFor(() => expect(repository.delete).toHaveBeenCalled())
    const [request] = vi.mocked(repository.delete).mock.calls[0]
    expect('excludedIds' in request && [...request.excludedIds].sort()).toEqual(['11', '12', '13'])
  })
})

it('편집 바는 보관함 창의 스크롤 여백까지 덮어 위에 붙을 때 목록이 비쳐 보이지 않는다', async () => {
  const { readFileSync } = await import('node:fs')
  const { resolve } = await import('node:path')
  const inbox = readFileSync(resolve(process.cwd(), 'src/notifications/NotificationInbox.module.css'), 'utf8')
  const modal = readFileSync(resolve(process.cwd(), 'src/user/auth/MemberMenuModal.module.css'), 'utf8')
  const header = inbox.match(/\.editHeader\s*\{[^}]*\}/)?.[0] ?? ''
  const bar = inbox.match(/\.editToolbar\s*\{[^}]*\}/)?.[0] ?? ''
  expect(header).toMatch(/position:\s*sticky;/)
  expect(header).toMatch(/top:\s*calc\(-1 \* var\(--inbox-scroll-padding, 0px\)\);/)
  expect(header).toMatch(/margin-top:\s*calc\(-1 \* var\(--inbox-scroll-padding, 0px\)\);/)
  expect(header).toMatch(/padding-top:\s*var\(--inbox-scroll-padding, 0px\);/)
  expect(header).toMatch(/background:\s*var\(--ds-color-surface\);/)
  expect(bar).not.toMatch(/position:\s*sticky/)
  expect(modal).toMatch(/\.panel\s*\{[^}]*--inbox-scroll-padding:\s*24px;/)
})

it('편집 바의 삭제 버튼은 주변 줄보다 작게 둔다', async () => {
  const { readFileSync } = await import('node:fs')
  const { resolve } = await import('node:path')
  const inbox = readFileSync(resolve(process.cwd(), 'src/notifications/NotificationInbox.module.css'), 'utf8')
  const rule = inbox.match(/\.editToolbar \.deleteButton\s*\{[^}]*\}/)?.[0] ?? ''
  expect(rule).toMatch(/min-height:\s*36px;/)
  expect(rule).toMatch(/padding:\s*0 12px;/)
  expect(rule).toMatch(/font-size:\s*12px;/)
})

it('편집 중 위 두 줄은 위아래 여백을 줄여 촘촘하게 둔다', async () => {
  const { readFileSync } = await import('node:fs')
  const { resolve } = await import('node:path')
  const inbox = readFileSync(resolve(process.cwd(), 'src/notifications/NotificationInbox.module.css'), 'utf8')
  expect(inbox).toMatch(/\.editHeader \.toolbar\s*\{[^}]*margin-bottom:\s*0;/)
  expect(inbox).toMatch(/\.editHeader \.textButton\s*\{[^}]*min-height:\s*36px;/)
  expect(inbox).toMatch(/\.selectAll\s*\{[^}]*min-height:\s*36px;/)
  expect(inbox.match(/\.editToolbar\s*\{[^}]*\}/)?.[0]).toMatch(/padding:\s*0 0 8px;/)
})

it('보관함만 여는 창은 제목과 편집 줄 사이 위쪽 여백을 줄인다', async () => {
  const { readFileSync } = await import('node:fs')
  const { resolve } = await import('node:path')
  const css = readFileSync(resolve(process.cwd(), 'src/user/auth/MemberMenuModal.module.css'), 'utf8')
  const tsx = readFileSync(resolve(process.cwd(), 'src/user/auth/MemberMenuModal.tsx'), 'utf8')
  expect(css).toMatch(/\.panel\.inboxPanel\s*\{[^}]*--inbox-scroll-padding:\s*8px;/)
  expect(tsx).toMatch(/inboxOnly \? <div className=\{`\$\{styles\.panel\} \$\{styles\.inboxPanel\}`\}>/)
})
