import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementList } from './ManagementList'
import { ManagementSummaryTable } from './ManagementSummaryTable'
import { parseManagementDetail, type ManagementPage, type ManagementSummary } from './managementContract'

const mocks = vi.hoisted(() => ({ getManagementPage: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...mocks }))

const legacy: ManagementSummary = { id: 7, name: '두꺼비 단지', subtitle: '서울 중구 세종대로 1', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
const complex: ManagementSummary = { ...legacy, complex: { sourceIdentifier: 'MYHOME-7', completionDate: '2020-03-01', totalHouseholdCount: 0, totalParkingCount: 0, heatingType: 'DISTRICT', buildingType: 'APARTMENT', corridorType: null, hasElevator: false, moveOutCountLastYear: null }, announcement: null }
const announcement: ManagementSummary = { ...legacy, name: '두꺼비 공고', subtitle: '2026-10-01', announcement: { sourceIdentifier: 'LH-7', originalUrl: 'https://notice.example.com/7', recruitmentType: 'WAITLIST', postedDate: '2026-10-01', applicationStartDate: '2026-10-10', applicationEndDate: '2026-10-15', winnerAnnouncementDate: '2026-10-30' }, complex: null }
function page(item = complex, number = 0): ManagementPage { return { items: [item], page: number, hasNext: number < 2, totalElements: 41, totalPages: 3 } }
function deferred<T>() {
  let resolve: (value: T) => void = () => { throw new Error('Promise not initialized') }
  let reject: (reason: Error) => void = () => { throw new Error('Promise not initialized') }
  const promise = new Promise<T>((onResolve, onReject) => { resolve = onResolve; reject = onReject })
  return { promise, resolve, reject }
}
beforeEach(() => { vi.clearAllMocks(); mocks.getManagementPage.mockResolvedValue(page()) })

it('단지 필드를 각 열로 표시하고 0·미설치·미확인을 구분한다', () => {
  render(<MemoryRouter><ManagementSummaryTable items={[complex]} resource="complexes" returnTo="/admin/complexes?provider=LH" /></MemoryRouter>)
  expect(screen.getByRole('columnheader', { name: '전체 세대수 (totalHouseholdCount)' })).toBeVisible()
  expect(screen.getByRole('columnheader', { name: '관리자 최종 변경 (updatedAt)' })).toBeVisible()
  const row = screen.getByRole('rowheader', { name: '두꺼비 단지 미검토' }).closest('tr')!
  expect(within(row).getByText('미검토')).toBeVisible()
  expect(within(row).getAllByRole('cell', { name: '0' })).toHaveLength(2)
  expect(within(row).getByRole('cell', { name: '미설치' })).toBeVisible()
  expect(within(row).getAllByRole('cell', { name: '미확인' })).toHaveLength(3)
  expect(within(row).getByRole('cell', { name: '지역난방' })).toBeVisible()
  expect(within(row).getByRole('cell', { name: '2020-03-01' })).toBeVisible()
  expect(screen.getByRole('link', { name: '두꺼비 단지' })).toHaveAttribute('href', '/admin/complexes/7?returnTo=%2Fadmin%2Fcomplexes%3Fprovider%3DLH')
  expect(screen.getByText('등록됨')).toHaveAttribute('title', expect.stringContaining('모집 상태를 뜻하지 않습니다'))
})

it('공고 일정·모집 유형·출처를 표시하고 URL 링크와 툴팁에서 키를 제거한다', () => {
  const item: ManagementSummary = { ...announcement, announcement: { ...announcement.announcement!, originalUrl: 'https://user:secret@notice.example.com/7?category=housing&serviceKey=do-not-show' } }
  render(<MemoryRouter><ManagementSummaryTable items={[item]} resource="announcements" /></MemoryRouter>)
  expect(screen.getByRole('columnheader', { name: '접수 시작일 (applicationStartDate)' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '예비 입주자' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '2026-10-10' })).toBeVisible()
  const url = 'https://notice.example.com/7?category=housing'
  expect(screen.getByRole('link', { name: url })).toHaveAttribute('href', url)
  expect(screen.getByRole('link', { name: url })).toHaveAttribute('title', url)
  expect(screen.queryByText(/do-not-show|secret/)).not.toBeInTheDocument()
})

it('관리자 변경 시각은 한국 시각으로 표시하고 도메인 날짜는 그대로 보존한다', () => {
  render(<MemoryRouter><ManagementSummaryTable items={[{ ...complex, updatedAt: '2026-10-03T03:04:05Z' }]} resource="complexes" /></MemoryRouter>)
  expect(screen.getByRole('cell', { name: /2026\. 10\. 3\. (12:04:05|12시 4분 5초)/ })).toBeVisible()
  expect(screen.getByRole('cell', { name: '2020-03-01' })).toBeVisible()
})

it('안전하지 않은 공고 원문 URL을 실행 가능한 링크로 표시하지 않는다', () => {
  render(<MemoryRouter><ManagementSummaryTable items={[{ ...announcement, announcement: { ...announcement.announcement!, originalUrl: 'javascript:alert(1)' } }]} resource="announcements" /></MemoryRouter>)
  expect(screen.getByText('URL 형식 확인 필요')).toBeVisible()
  expect(screen.getAllByRole('link')).toHaveLength(1)
})

it('기존 요약 응답도 상세 연결 표에 표시하고 게시일은 subtitle을 사용한다', () => {
  render(<MemoryRouter><ManagementSummaryTable items={[{ ...legacy, subtitle: '2026-10-01' }]} resource="announcements" /></MemoryRouter>)
  expect(screen.getByRole('cell', { name: '2026-10-01' })).toBeVisible()
  expect(screen.getByRole('link', { name: '두꺼비 단지' })).toHaveAttribute('href', '/admin/announcements/7')
  expect(screen.getByText('기록 없음')).toBeVisible()
})

it('같은 조건의 페이지 이동 중 표와 스크롤 영역을 유지하고 완료 후 행만 바꾼다', async () => {
  const next = deferred<ManagementPage>()
  mocks.getManagementPage.mockResolvedValueOnce(page()).mockReturnValueOnce(next.promise)
  render(<MemoryRouter><ManagementList resource="complexes" /></MemoryRouter>)
  const region = await screen.findByRole('region', { name: '정제 단지 목록 가로 스크롤' })
  const table = screen.getByRole('table')
  region.scrollTop = 170; region.scrollLeft = 240
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('region')).toBe(region)
  expect(screen.getByRole('table')).toBe(table)
  expect(screen.getByRole('link', { name: '두꺼비 단지' })).toBeVisible()
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  expect(table.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true')
  await act(() => next.resolve(page({ ...complex, id: 8, name: '다음 단지' }, 1)))
  expect(screen.getByRole('table')).toBe(table)
  expect(region.scrollTop).toBe(170); expect(region.scrollLeft).toBe(240)
  expect(screen.getByRole('link', { name: '다음 단지' })).toBeVisible()
  expect(screen.getByRole('button', { name: '다음' })).toBeEnabled()
  expect(mocks.getManagementPage.mock.lastCall?.[1].get('page')).toBe('1')
})

it('검색 조건을 바꾸면 이전 조건의 행을 숨기고 취소된 이전 응답을 무시한다', async () => {
  const oldRequest = deferred<ManagementPage>(), newRequest = deferred<ManagementPage>()
  mocks.getManagementPage.mockResolvedValueOnce(page()).mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
  render(<MemoryRouter><ManagementList resource="complexes" /></MemoryRouter>)
  await screen.findByRole('table')
  fireEvent.change(screen.getByLabelText('단지명·주소'), { target: { value: '부산' } })
  fireEvent.submit(screen.getByRole('button', { name: '검색' }).closest('form')!)
  expect(screen.queryByRole('link', { name: '두꺼비 단지' })).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('단지명·주소'), { target: { value: '서울' } })
  fireEvent.submit(screen.getByRole('button', { name: '검색' }).closest('form')!)
  await act(() => newRequest.resolve(page({ ...complex, id: 9, name: '서울 단지' })))
  await act(() => oldRequest.resolve(page({ ...complex, id: 8, name: '부산 단지' })))
  expect(screen.getByRole('link', { name: '서울 단지' })).toBeVisible()
  expect(screen.queryByRole('link', { name: '부산 단지' })).not.toBeInTheDocument()
})

it('단지에서 공고로 전환할 때 단지 행을 공고 표에 표시하지 않는다', async () => {
  const next = deferred<ManagementPage>()
  mocks.getManagementPage.mockResolvedValueOnce(page()).mockReturnValueOnce(next.promise)
  const view = render(<MemoryRouter><ManagementList resource="complexes" /></MemoryRouter>)
  await screen.findByRole('table')
  view.rerender(<MemoryRouter><ManagementList resource="announcements" /></MemoryRouter>)
  expect(screen.queryByLabelText('검토 상태')).not.toBeInTheDocument()
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '두꺼비 단지' })).not.toBeInTheDocument()
  await act(() => next.resolve(page(announcement)))
  expect(screen.getByRole('table', { name: '정제 공고 목록' })).toBeVisible()
})

it('검토 상태로 검색하고 페이지 이동과 상세 복귀 링크에 조건을 유지한다', async () => {
  const reviewed = { ...complex, complex: { ...complex.complex!, verificationStatus: 'VERIFIED' as const, reviewedFieldCount: 2 } }
  mocks.getManagementPage.mockResolvedValue(page(reviewed))
  render(<MemoryRouter initialEntries={['/admin/complexes?page=2']}><ManagementList resource="complexes" /></MemoryRouter>)
  await screen.findByRole('table')
  fireEvent.change(screen.getByLabelText('검토 상태'), { target: { value: 'VERIFIED' } })
  fireEvent.submit(screen.getByRole('button', { name: '검색' }).closest('form')!)
  await screen.findByRole('table')
  expect(mocks.getManagementPage.mock.lastCall?.[1].get('verification')).toBe('VERIFIED')
  expect(mocks.getManagementPage.mock.lastCall?.[1].has('page')).toBe(false)
  expect(screen.getByRole('link', { name: '두꺼비 단지' }).closest('th')).toHaveTextContent('확인 완료 · 2/6항목')
  expect(screen.getByRole('link', { name: '두꺼비 단지' })).toHaveAttribute('href', expect.stringContaining('verification%3DVERIFIED'))
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await act(async () => {})
  expect(mocks.getManagementPage.mock.lastCall?.[1].get('verification')).toBe('VERIFIED')
  expect(mocks.getManagementPage.mock.lastCall?.[1].get('page')).toBe('1')
})

it('페이지 조회 실패 후 재시도하는 동안 기존 표를 유지한다', async () => {
  const retry = deferred<ManagementPage>()
  mocks.getManagementPage.mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error('연결 실패')).mockReturnValueOnce(retry.promise)
  render(<MemoryRouter><ManagementList resource="complexes" /></MemoryRouter>)
  const table = await screen.findByRole('table')
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
  expect(screen.getByRole('table')).toBe(table)
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(screen.getByRole('table')).toBe(table)
  await act(() => retry.resolve(page(complex, 1)))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByText('2 / 3 페이지')).toBeVisible()
})

it('새 요약 데이터는 검증하고 이전 응답의 누락 필드는 호환한다', () => {
  const detail = (summary: unknown) => ({ summary, sourceIdentifier: 'TEST', data: { version: 1 } })
  expect(parseManagementDetail(detail(complex)).summary.complex?.totalHouseholdCount).toBe(0)
  expect(parseManagementDetail(detail(announcement)).summary.announcement?.postedDate).toBe('2026-10-01')
  expect(parseManagementDetail(detail(legacy)).summary.name).toBe('두꺼비 단지')
  for (const complexValue of [{ ...complex.complex, totalParkingCount: -1 }, { ...complex.complex, hasElevator: 'false' }, { ...complex.complex, totalHouseholdCount: '0' }, { ...complex.complex, completionDate: 2020 }]) {
    expect(() => parseManagementDetail(detail({ ...legacy, complex: complexValue }))).toThrow('상세 응답이 올바르지 않습니다.')
  }
  expect(() => parseManagementDetail(detail({ ...legacy, announcement: { ...announcement.announcement, applicationStartDate: null } }))).toThrow('상세 응답이 올바르지 않습니다.')
})
