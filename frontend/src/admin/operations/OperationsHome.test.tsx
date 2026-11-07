import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { OperationsHome } from './OperationsHome'
import { loadAnnouncementOverview, loadCollectionOverview, loadComplexOverview, loadUserOverview } from './api'
import { listFeedback } from '../feedback/api'
import { listDeliveries } from '../notifications/deliveryApi'
import { loadNotificationSubscriptions, loadNotificationTrend } from '../notifications/api'
vi.mock('./api', () => ({ loadAnnouncementOverview: vi.fn(), loadCollectionOverview: vi.fn(), loadComplexOverview: vi.fn(), loadUserOverview: vi.fn() }))
vi.mock('../feedback/api', () => ({ listFeedback: vi.fn() }))
vi.mock('../notifications/deliveryApi', () => ({ listDeliveries: vi.fn() }))
vi.mock('../notifications/api', () => ({ loadNotificationSubscriptions: vi.fn(), loadNotificationTrend: vi.fn() }))
const asOf = '2026-11-07T00:00:00Z'
const page = { page: 0, totalElements: 3, totalPages: 1, hasNext: false, items: [] }
const complex = { total: 8427, recruiting: 276, missingCoordinates: 18, reviewRequired: 7, agencies: { LH: 6831, SH: 1104, GH: 492 }, asOf }
const userOverview = (days: number) => ({ total: 1842, newUsers: days === 7 ? 42 : 186, previousNewUsers: 35,
  trend: Array.from({ length: days }, (_, i) => ({ day: new Date(Date.UTC(2026, 10, 7 - days + 1 + i)).toISOString().slice(0, 10), count: i })), asOf })
const trend = (): Awaited<ReturnType<typeof loadNotificationTrend>> => ({ asOf, collectionStartedAt: '2026-08-01T00:00:00Z', days: Array.from({ length: 90 }, (_, i) => ({
  day: new Date(Date.UTC(2026, 10, 7 - 90 + 1 + i)).toISOString().slice(0, 10), activations: i >= 83 ? 3 : 1, cancellations: 0,
})) })
function show() { return render(<MemoryRouter><OperationsHome /></MemoryRouter>) }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(loadComplexOverview).mockResolvedValue(complex)
  vi.mocked(loadAnnouncementOverview).mockResolvedValue({ statuses: { BEFORE_APPLICATION: 24, APPLYING: 38, CLOSED: 3071, CONDITIONAL: 5, CANCELLED: 5, UNKNOWN: 13 }, attention: 21, reasons: { SCHEDULE_MISSING: 13, SUPPLY_MISSING: 8, UNCONFIRMED: 5 }, asOf })
  vi.mocked(loadCollectionOverview).mockResolvedValue({ sources: [
    { agency: 'LH', lastSuccessAt: '2026-11-06T09:10:00Z', latestStartedAt: asOf, latestStatus: 'FAILED' },
    { agency: 'SH', lastSuccessAt: null, latestStartedAt: null, latestStatus: null },
    { agency: 'GH', lastSuccessAt: asOf, latestStartedAt: asOf, latestStatus: 'COMPLETED' },
  ], asOf })
  vi.mocked(loadUserOverview).mockImplementation(async days => userOverview(days))
  vi.mocked(listFeedback).mockResolvedValue(page)
  vi.mocked(listDeliveries).mockImplementation(async params => ({ ...page, totalElements: params.get('status') === 'FAILED' ? 2 : 4 }))
  vi.mocked(loadNotificationSubscriptions).mockResolvedValue({ ...page, summary: { activeMembers: 128, activeSubscriptions: 347, asOf } })
  vi.mocked(loadNotificationTrend).mockResolvedValue(trend())
})
it('네 패널의 집계와 기존 관리 페이지의 지원 필터를 연결한다', async () => {
  show()
  await waitFor(() => expect(screen.getByLabelText('전체 단지 건수')).toHaveTextContent('8,427'))
  for (const name of ['단지', '공고', '유저', '알림']) expect(screen.getByRole('region', { name })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: /재검토 7/ })).toHaveAttribute('href', '/admin/complexes?review=true')
  expect(screen.getByRole('link', { name: /LH 6,831/ })).toHaveAttribute('href', '/admin/complexes?provider=LH')
  expect(screen.getByRole('link', { name: /미확정 5/ })).toHaveAttribute('href', '/admin/announcements?unconfirmed=true')
  expect(screen.getByRole('link', { name: /새 사용자 의견 3/ })).toHaveAttribute('href', '/admin/feedback?status=OPEN')
  expect(screen.getByRole('link', { name: /발송 실패 2/ })).toHaveAttribute('href', '/admin/notification-deliveries?status=FAILED')
  expect(screen.getByRole('link', { name: /재시도 대기 4/ })).toHaveAttribute('href', '/admin/notification-deliveries?status=RETRY')
  expect(screen.getByRole('link', { name: /모집중/ })).toHaveAttribute('title', expect.stringContaining('필터 추가'))
  expect(screen.getByRole('button', { name: /카카오톡 메시지 발송/ })).toBeInTheDocument()
})
it('기간을 바꾸면 가입과 신청·취소만 바뀌고 현재 현황은 유지한다', async () => {
  show()
  await waitFor(() => expect(screen.getByLabelText('신규 가입 건수')).toHaveTextContent('42'))
  expect(screen.getByLabelText('최근 7일 신청')).toHaveTextContent('21')
  fireEvent.click(screen.getByRole('button', { name: '30일' }))
  await waitFor(() => expect(screen.getByLabelText('신규 가입 건수')).toHaveTextContent('186'))
  expect(screen.getByLabelText('전체 단지 건수')).toHaveTextContent('8,427')
  expect(screen.getByLabelText('전체 회원 건수')).toHaveTextContent('1,842')
  expect(screen.getByLabelText('활성 알림 건수')).toHaveTextContent('347')
  expect(loadComplexOverview).toHaveBeenCalledTimes(1)
  expect(loadNotificationTrend).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('link', { name: /^신청 [0-9]/ })).toHaveAttribute('href', '/admin/notification-subscriptions?view=trend&days=30')
})
it('기간 전환 전에 시작한 늦은 응답은 새 기간 집계를 덮어쓰지 않는다', async () => {
  let finishOld!: (value: ReturnType<typeof userOverview>) => void
  vi.mocked(loadUserOverview).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
  show()
  fireEvent.click(screen.getByRole('button', { name: '30일' }))
  await waitFor(() => expect(screen.getByLabelText('신규 가입 건수')).toHaveTextContent('186'))
  await act(async () => finishOld(userOverview(7)))
  expect(screen.getByLabelText('신규 가입 건수')).toHaveTextContent('186')
})
it('조회 실패를 0건으로 표시하지 않고 실패한 영역만 재시도한다', async () => {
  vi.mocked(loadComplexOverview).mockRejectedValueOnce(new Error('연결 실패'))
  show()
  await waitFor(() => expect(screen.getByLabelText('전체 단지 건수')).toHaveTextContent('조회 실패'))
  expect(screen.getByLabelText('공고중 건수')).toHaveTextContent('24')
  fireEvent.click(within(screen.getByRole('region', { name: '단지' })).getByRole('button', { name: '다시 불러오기' }))
  await waitFor(() => expect(screen.getByLabelText('전체 단지 건수')).toHaveTextContent('8,427'))
  expect(loadComplexOverview).toHaveBeenCalledTimes(2)
  expect(loadAnnouncementOverview).toHaveBeenCalledTimes(1)
})
it('실제 0건과 미집계 날짜가 포함된 기간을 구분한다', async () => {
  vi.mocked(loadComplexOverview).mockResolvedValue({ ...complex, total: 0 })
  const partial = trend(); partial.days[89].activations = null; partial.days[89].cancellations = null
  vi.mocked(loadNotificationTrend).mockResolvedValue(partial)
  show()
  await waitFor(() => expect(screen.getByLabelText('전체 단지 건수')).toHaveTextContent('0'))
  expect(screen.getByLabelText('최근 7일 신청')).toHaveTextContent('미집계')
  expect(screen.getByRole('row', { name: /2026-11-07 미집계 미집계/ })).toBeInTheDocument()
  expect(screen.getByLabelText('활성 알림 건수')).toHaveTextContent('347')
})
it('최근 실패와 마지막 성공, 성공 이력이 없는 기관을 구분한다', async () => {
  show()
  const table = await screen.findByRole('table', { name: '기관별 공고 수집·정제 실행' })
  await waitFor(() => expect(within(table).getByRole('row', { name: /LH/ })).toHaveTextContent('실패'))
  expect(within(table).getByRole('row', { name: /LH/ })).toHaveTextContent('11. 06. 18:10')
  expect(within(table).getByRole('row', { name: /SH/ })).toHaveTextContent('성공 이력 없음')
  expect(within(table).getAllByRole('link').every(link => link.getAttribute('href') === '/admin/ingest/announcements')).toBe(true)
})
