import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { OperationsHome } from './OperationsHome'
import { changePdfAutoExtraction, loadPdfAutoExtraction, loadPdfReviews, loadAnnouncementOverview, loadCollectionOverview, loadComplexOverview, loadTasksOverview, loadUserOverview } from './api'
import { listFeedback } from '../feedback/api'
import { listDeliveries } from '../notifications/deliveryApi'
import { loadNotificationSubscriptions, loadNotificationTrend } from '../notifications/api'
vi.mock('./api', () => ({ changePdfAutoExtraction: vi.fn(), loadPdfAutoExtraction: vi.fn(), loadPdfReviews: vi.fn(), loadAnnouncementOverview: vi.fn(), loadCollectionOverview: vi.fn(), loadComplexOverview: vi.fn(), loadTasksOverview: vi.fn(), loadUserOverview: vi.fn() }))
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
const autoExtraction = { enabled: false, updatedBy: null, updatedAt: null, publicationMode: 'APPLY' as const, notExtracted: 85, inFlight: 0, maxInFlight: 2, asOf }
function show() { return render(<MemoryRouter><OperationsHome /></MemoryRouter>) }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(loadPdfReviews).mockResolvedValue({ items: [], nextBefore: null })
  vi.mocked(loadPdfAutoExtraction).mockResolvedValue(autoExtraction)
  vi.mocked(loadComplexOverview).mockResolvedValue(complex)
  vi.mocked(loadAnnouncementOverview).mockResolvedValue({ statuses: { BEFORE_APPLICATION: 24, APPLYING: 38, CLOSED: 3071, CONDITIONAL: 5, CANCELLED: 5, UNKNOWN: 13 }, attention: 21, reasons: { SCHEDULE_MISSING: 13, SUPPLY_MISSING: 8, UNCONFIRMED: 5 }, asOf })
  vi.mocked(loadCollectionOverview).mockResolvedValue({ sources: [
    { agency: 'LH', lastSuccessAt: '2026-11-06T09:10:00Z', latestStartedAt: asOf, latestStatus: 'FAILED' },
    { agency: 'SH', lastSuccessAt: null, latestStartedAt: null, latestStatus: null },
    { agency: 'GH', lastSuccessAt: asOf, latestStartedAt: asOf, latestStatus: 'COMPLETED' },
  ], asOf })
  vi.mocked(loadUserOverview).mockImplementation(async days => userOverview(days))
  vi.mocked(loadTasksOverview).mockResolvedValue({ stages: { PDF_MISSING: 1, NOT_EXTRACTED: 46, EXTRACTING: 2, EXTRACTION_FAILED: 3, REVIEW_PENDING: 4, CONFIRMED: 8, RECHECK_REQUIRED: 0 },
    queue: { waiting: 2, running: 1, oldestWaitSeconds: 754, recentFailed: 5 }, unresolvedFailures: 7, asOf })
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
  expect(screen.getByRole('link', { name: /미확정 5/ })).toHaveAttribute('href', '/admin/announcements?attention=UNCONFIRMED')
  expect(screen.getByRole('link', { name: /새 사용자 의견 3/ })).toHaveAttribute('href', '/admin/feedback?status=OPEN')
  expect(screen.getByRole('link', { name: /발송 실패 2/ })).toHaveAttribute('href', '/admin/notification-deliveries?status=FAILED')
  expect(screen.getByRole('link', { name: /재시도 대기 4/ })).toHaveAttribute('href', '/admin/notification-deliveries?status=RETRY')
  expect(screen.getByRole('link', { name: /모집중/ })).toHaveAttribute('href', '/admin/complexes?recruiting=true')
  expect(screen.queryByText(/카카오톡/)).not.toBeInTheDocument()
})
it('처리할 일은 마감 전 공고 단계별 수를 같은 범위의 공고 목록 필터로 연결하고 AI 큐와 미해결 실패를 보인다', async () => {
  show()
  const tasks = screen.getByRole('region', { name: '처리할 일' })
  await waitFor(() => expect(within(tasks).getByText('마감 전 공고 64')).toBeInTheDocument())
  const stages = within(within(tasks).getByRole('list', { name: '공고 처리 단계' }))
  expect(stages.getByRole('link', { name: '추출 실패 3' })).toHaveAttribute('href', '/admin/announcements?applicationStatus=OPEN&stage=EXTRACTION_FAILED')
  expect(stages.getByRole('link', { name: '검토 대기 4' })).toHaveAttribute('data-action', 'true')
  expect(stages.getByRole('link', { name: '추출 전 46' })).not.toHaveAttribute('data-action')
  expect(stages.getAllByRole('link')).toHaveLength(7)
  expect(within(tasks).getByText('AI 추출 대기').parentElement).toHaveTextContent('처리 중 1 · 가장 오래 기다림 12분 · 최근 24시간 실패 5')
  expect(within(tasks).getByRole('link', { name: '미해결 공고 문제 7' })).toHaveAttribute('href', '/admin/failures?domain=announcement&category=all')
  expect(within(tasks).getByRole('link', { name: '단지 문제 보기' })).toHaveAttribute('href', '/admin/failures?domain=complex&category=all')
  expect(within(tasks).queryByRole('region', { name: '자동 추출' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: /확인 필요 21/ })).toHaveAttribute('href', '/admin/announcements?attention=ANY')
})
it('처리할 일 조회가 실패하면 0건 대신 조회 실패를 보이고 다른 패널은 그대로 둔다', async () => {
  vi.mocked(loadTasksOverview).mockRejectedValueOnce(new Error('연결 실패'))
  show()
  const tasks = screen.getByRole('region', { name: '처리할 일' })
  await waitFor(() => expect(within(tasks).getByRole('alert')).toHaveTextContent('연결 실패'))
  expect(within(tasks).getByRole('link', { name: '추출 실패 조회 실패' })).toBeInTheDocument()
  await waitFor(() => expect(screen.getByLabelText('공고중 건수')).toHaveTextContent('24'))
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
  expect(within(table).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')])).toEqual(['LH', 'SH', 'GH'].map(agency => [agency, '/admin/ingest/announcements']))
})

it('자동 보정 검수함은 공고마다 제목·보류 사유 건수 한 줄로 PDF 보정 화면에 연결하고 사유 내용과 내부 경로는 보이지 않는다', async () => {
  vi.mocked(loadPdfReviews).mockResolvedValue({ items: [
    { jobId: 57, announcementId: 43, name: '국민임대 입주자모집', reason: 'complexes[0] 3쪽 공급호수 합계 불일치\n\ncells[].convertedDeposit 근거 쪽수 없음\n', eligibilityOnly: true },
    { jobId: 56, announcementId: 42, name: '영구임대 예비모집', reason: 'announcement.winnerAnnouncementDate 확인', eligibilityOnly: false },
  ], nextBefore: null })
  show()
  const queue = screen.getByRole('region', { name: '자동 보정 검수함' })
  const eligibility = await within(queue).findByRole('link', { name: /국민임대 입주자모집/ })
  expect(eligibility).toHaveAttribute('href', '/admin/announcements/43/pdf-correction')
  expect(eligibility).toHaveTextContent('보류 사유 2건')
  expect(within(eligibility).getByText('신청자격')).toBeVisible()
  const supply = within(queue).getByRole('link', { name: /영구임대 예비모집/ })
  expect(supply).toHaveAttribute('href', '/admin/announcements/42/pdf-correction')
  expect(supply).toHaveTextContent('보류 사유 1건')
  expect(within(supply).queryByText('신청자격')).not.toBeInTheDocument()
  expect(within(queue).getAllByRole('listitem')).toHaveLength(2)
  expect(queue).not.toHaveTextContent(/complexes|cells|convertedDeposit|winnerAnnouncementDate|공급호수|근거 쪽수/)
})

it('자동 추출을 켤 때 유료로 추출될 추출 전 공고 수와 동시 처리 수를 확인 창으로 묻고, 취소하면 바꾸지 않는다', async () => {
  const confirm = vi.spyOn(window, 'confirm')
  show()
  const card = screen.getByRole('region', { name: '자동 추출' })
  expect(await within(card).findByText('꺼짐')).toBeVisible()
  expect(card).toHaveTextContent('맞으면 자동 공개')
  expect(card).toHaveTextContent('최대 85건')
  expect(card).toHaveTextContent('관리자가 바꾼 적 없음')
  fireEvent.click(within(card).getByRole('button', { name: '자동 추출 켜기' }))
  const dialog = screen.getByRole('dialog', { name: '자동 추출을 켤까요?' })
  expect(dialog).toHaveTextContent('마감 전 추출 전 공고 최대 85건이 차례로 유료 추출됩니다(동시 2건).')
  expect(changePdfAutoExtraction).not.toHaveBeenCalled()
  fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '자동 추출을 켤까요?' })).not.toBeInTheDocument())
  expect(changePdfAutoExtraction).not.toHaveBeenCalled()
  expect(within(card).getByText('꺼짐')).toBeVisible()
  expect(confirm).not.toHaveBeenCalled()
  confirm.mockRestore()
})
it('켜기는 확인 창에서 실행을 눌러야 바뀌고 바꾼 사람을 보이며, 끌 때는 묻지 않는다', async () => {
  vi.mocked(changePdfAutoExtraction).mockResolvedValueOnce({ ...autoExtraction, enabled: true, updatedBy: 'admin-7', updatedAt: asOf, inFlight: 2, notExtracted: 83 })
    .mockResolvedValueOnce({ ...autoExtraction, updatedBy: 'admin-7', updatedAt: asOf })
  show()
  const card = screen.getByRole('region', { name: '자동 추출' })
  fireEvent.click(await within(card).findByRole('button', { name: '자동 추출 켜기' }))
  fireEvent.click(within(screen.getByRole('dialog', { name: '자동 추출을 켤까요?' })).getByRole('button', { name: '자동 추출 켜기' }))
  expect(await within(card).findByText('켜짐')).toBeVisible()
  expect(changePdfAutoExtraction).toHaveBeenCalledWith(true)
  expect(card).toHaveTextContent('마지막 변경 admin-7')
  expect(card).toHaveTextContent('2 / 동시 2건')
  fireEvent.click(within(card).getByRole('button', { name: '자동 추출 끄기' }))
  expect(screen.queryByRole('dialog', { name: /자동 추출/ })).not.toBeInTheDocument()
  expect(await within(card).findByText('꺼짐')).toBeVisible()
  expect(changePdfAutoExtraction).toHaveBeenLastCalledWith(false)
})
it('자동 추출을 바꾸지 못하면 이전 상태를 그대로 두고 실패를 알린다', async () => {
  vi.mocked(changePdfAutoExtraction).mockRejectedValueOnce(new Error('로그인 상태를 확인해 주세요.'))
  show()
  const card = screen.getByRole('region', { name: '자동 추출' })
  fireEvent.click(await within(card).findByRole('button', { name: '자동 추출 켜기' }))
  fireEvent.click(within(screen.getByRole('dialog', { name: '자동 추출을 켤까요?' })).getByRole('button', { name: '자동 추출 켜기' }))
  expect(await within(card).findByRole('alert')).toHaveTextContent('변경 실패: 로그인 상태를 확인해 주세요.')
  expect(within(card).getByText('꺼짐')).toBeVisible()
  expect(within(card).getByRole('button', { name: '자동 추출 켜기' })).toBeEnabled()
})
it('새로고침은 현황 요약을 다시 불러온다', async () => {
  show()
  await waitFor(() => expect(screen.getByLabelText('전체 단지 건수')).toHaveTextContent('8,427'))
  const calls = vi.mocked(loadComplexOverview).mock.calls.length
  fireEvent.click(screen.getByRole('button', { name: '현황 새로고침' }))
  await waitFor(() => expect(vi.mocked(loadComplexOverview).mock.calls.length).toBe(calls + 1))
  expect(screen.getByRole('button', { name: '현황 새로고침' })).toHaveTextContent('새로고침')
})
it('숫자 기준 버튼 하나가 항목별 집계 기준을 한 대화상자에 목록으로 열고 닫기로 닫는다', async () => {
  show()
  expect(screen.getAllByRole('button', { name: /기준/ })).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: '숫자 기준' }))
  const dialog = screen.getByRole('dialog', { name: '숫자 기준' })
  expect(dialog).toHaveAttribute('open')
  expect(within(dialog).getAllByRole('term').map(term => term.textContent)).toEqual(['기간 적용', '처리할 일', '단지 집계', '공고 집계', '확인 필요', '유저 집계', '알림 집계', '연결 기준'])
  expect(dialog).toHaveTextContent('좌표 미확인은 위도 또는 경도가 없는 단지')
  fireEvent.click(within(dialog).getByRole('button', { name: '도움말 닫기' }))
  expect(dialog).not.toHaveAttribute('open')
})
