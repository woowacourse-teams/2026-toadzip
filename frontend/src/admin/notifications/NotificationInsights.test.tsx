import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { NotificationManagementPage } from './NotificationSubscriptions'

afterEach(() => vi.unstubAllGlobals())
const empty = { items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0,
  summary: { activeMembers: 0, activeSubscriptions: 0, asOf: '2026-10-14T00:00:00Z' } }
function Location() { const location = useLocation(); return <output aria-label="현재 경로">{location.search}</output> }
function setup() {
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/targets')) return Response.json({ data: { items: [{ targetType: 'REGION', targetId: '11',
      targetName: '서울특별시 전체', activeMembers: 2 }], page: 0, totalElements: 1, totalPages: 1,
      asOf: '2026-10-14T00:00:00Z' } })
    if (url.includes('/trend')) return Response.json({ data: { collectionStartedAt: '2026-10-14T00:00:00Z',
      asOf: '2026-10-14T01:00:00Z', days: [
        { day: '2026-10-13', activations: null, cancellations: null },
        { day: '2026-10-14', activations: 3, cancellations: 1 },
      ] } })
    return Response.json({ data: empty })
  })
  vi.stubGlobal('fetch', fetcher)
  render(<MemoryRouter initialEntries={['/admin/notification-subscriptions']}><Location /><NotificationManagementPage /></MemoryRouter>)
  return fetcher
}

it('대상별 신청 현황에서 대상의 신청자 목록으로 이동한다', async () => {
  setup()
  fireEvent.click(screen.getByRole('link', { name: '대상별 현황' }))
  fireEvent.click(await screen.findByRole('link', { name: '서울특별시 전체 신청자 보기' }))
  await screen.findByText('표시할 알림 신청이 없습니다.')
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('targetId=11')
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('targetType=REGION')
})

it('확인 필요 목록은 전체 상태를 조회하며 확인 사유를 구분한다', async () => {
  const fetcher = setup()
  fireEvent.click(screen.getByRole('link', { name: '확인 필요' }))
  await waitFor(() => expect(fetcher.mock.calls.some(([input]) =>
    String(input).includes('review=ANY') && String(input).includes('status=ALL'))).toBe(true))
  fireEvent.change(screen.getByLabelText('확인 항목'), { target: { value: 'REQUEST_DATE_UNKNOWN' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(fetcher.mock.calls.some(([input]) => String(input).includes('review=REQUEST_DATE_UNKNOWN'))).toBe(true))
})

it('기간별 추이에서 수집 전 날짜는 미집계로 표시하고 기간을 바꾼다', async () => {
  const fetcher = setup()
  fireEvent.click(screen.getByRole('link', { name: '신청·취소 추이' }))
  const table = await screen.findByRole('table', { name: '일별 신청·취소' })
  expect(table).toHaveTextContent('2026-10-14')
  expect(table).not.toHaveTextContent('2026-10-13')
  fireEvent.click(screen.getByRole('button', { name: '미집계 1일 보기' }))
  expect(table).toHaveTextContent('2026-10-13')
  expect(table).toHaveTextContent('미집계')
  fireEvent.click(screen.getByRole('button', { name: '미집계 날짜 접기' }))
  expect(table).not.toHaveTextContent('2026-10-13')
  expect(screen.getByText('집계 기간 신청')).toBeVisible()
  fireEvent.change(screen.getByLabelText('조회 기간'), { target: { value: '7' } })
  await waitFor(() => expect(fetcher.mock.calls.some(([input]) => String(input).includes('/trend?days=7'))).toBe(true))
})

it('확인 필요 목록의 초기화는 해당 보기와 기본 점검 조건을 유지한다', async () => {
  const fetcher = setup()
  fireEvent.click(screen.getByRole('link', { name: '확인 필요' }))
  await screen.findByText('표시할 알림 신청이 없습니다.')
  fireEvent.change(screen.getByLabelText('확인 항목'), { target: { value: 'REQUEST_DATE_UNKNOWN' } })
  fireEvent.change(screen.getByLabelText('신청 상태'), { target: { value: 'CANCELLED' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(String(fetcher.mock.lastCall?.[0])).toContain('review=REQUEST_DATE_UNKNOWN'))
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  await waitFor(() => expect(String(fetcher.mock.lastCall?.[0])).toContain('review=ANY'))
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('view=review')
  expect(screen.getByLabelText('신청 상태')).toHaveValue('ALL')
  expect(screen.getByLabelText('확인 항목')).toHaveValue('ANY')
})

it('대상 조건을 바꾸어 성공한 뒤 이전 조건으로 돌아오면 과거 오류가 남지 않는다', async () => {
  const fetcher = setup()
  fetcher.mockRejectedValueOnce(new Error('일시적인 조회 실패'))
  fireEvent.click(screen.getByRole('link', { name: '대상별 현황' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('일시적인 조회 실패')
  fireEvent.change(screen.getByLabelText('현황 대상'), { target: { value: 'REGION' } })
  await screen.findByRole('link', { name: '서울특별시 전체 신청자 보기' })
  fireEvent.change(screen.getByLabelText('현황 대상'), { target: { value: '' } })
  await screen.findByRole('link', { name: '서울특별시 전체 신청자 보기' })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('잘못된 추이 기간은 API를 호출하지 않으며 정상 보기로 복귀할 수 있다', () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  render(<MemoryRouter initialEntries={['/admin/notification-subscriptions?view=trend&days=999']}>
    <NotificationManagementPage /></MemoryRouter>)
  expect(screen.getByRole('alert')).toHaveTextContent('검색 조건을 확인해 주세요.')
  expect(fetcher).not.toHaveBeenCalled()
})
