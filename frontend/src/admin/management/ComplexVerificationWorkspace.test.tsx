import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ManagementWorkspace } from './ManagementWorkspace'
import { ManagementDetail } from './ManagementDetail'
import type { ComplexReview, ComplexVerification } from './complexVerificationContract'
import type { ManagementDetailData, ManagementSummary } from './managementContract'

const api = vi.hoisted(() => ({ getManagementPage: vi.fn(), getManagementDetail: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...api }))
const verification = vi.hoisted(() => ({ getComplexVerification: vi.fn(), saveComplexReview: vi.fn() }))
vi.mock('./complexVerificationApi', () => verification)

const summary: ManagementSummary = {
  id: 7, name: '검토 단지', subtitle: '서울 중구 세종대로 110', provider: 'LH', rental: 'HAPPY_HOUSING',
  deleted: false, modified: false, reviewRequired: false, updatedAt: null,
  complex: { sourceIdentifier: '12:HAPPY_HOUSING', completionDate: null, totalHouseholdCount: 300,
    totalParkingCount: 0, heatingType: null, buildingType: null, corridorType: null,
    hasElevator: null, moveOutCountLastYear: null, verificationStatus: 'UNREVIEWED', reviewedFieldCount: 0 },
}
function detail(item = summary): ManagementDetailData {
  return { summary: item, sourceIdentifier: '12:HAPPY_HOUSING', data: { version: 2, name: item.name,
    agencyCode: 'LH', rentalType: 'HAPPY_HOUSING', totalHouseholdCount: 300,
    address: { roadAddress: item.subtitle, pnu: '1114010100100010000', legalDongCode: '1114010100',
      provinceCode: '11', cityCountyDistrictCode: '11140', latitude: 37.5, longitude: 127 } },
  housingTypes: [], announcements: [], supplyRows: [], scheduleReviewed: false, schedules: [] }
}
function evidence(): ComplexVerification {
  return { version: 2, snapshotToken: 'a'.repeat(64), status: 'UNREVIEWED',
    currentValues: { NAME: summary.name, ADDRESS: { roadAddress: summary.subtitle, pnu: '1114010100100010000',
      legalDongCode: '1114010100', provinceCode: '11', cityCountyDistrictCode: '11140' },
    LOCATION: [37.5, 127], AGENCY: 'LH', RENTAL_TYPE: 'HAPPY_HOUSING', HOUSEHOLD_COUNT: 300 },
  sources: [], latestReview: null, history: [] }
}
function page(item = summary) { return { items: [item], page: 0, totalPages: 1, totalElements: 1, hasNext: false } }
function show(path = '/admin/complexes/7?provider=LH&verification=UNREVIEWED') {
  render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/admin/complexes/:id?" element={<ManagementWorkspace resource="complexes" />} />
  </Routes></MemoryRouter>)
}
beforeEach(() => {
  vi.clearAllMocks()
  api.getManagementPage.mockResolvedValue(page())
  api.getManagementDetail.mockResolvedValue(detail())
  verification.getComplexVerification.mockResolvedValue(evidence())
})
afterEach(() => vi.restoreAllMocks())

it('별도 상세 화면의 수정과 삭제도 작성 중인 검토의 이동 취소를 보존한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<MemoryRouter initialEntries={['/admin/complexes/7']}><Routes>
    <Route path="/admin/complexes/:id" element={<ManagementDetail resource="complexes" />} />
  </Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '데이터 검증' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '작성 중인 근거' } })
  fireEvent.click(screen.getByRole('button', { name: '수정' }))
  fireEvent.click(screen.getByRole('button', { name: '삭제' }))
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('이름')).not.toBeInTheDocument()
  expect(screen.getByLabelText('확인 근거·메모')).toHaveValue('작성 중인 근거')
  expect(screen.getByRole('checkbox', { name: '단지명 확인' })).toBeChecked()
})

it('편집 패널에서 검토를 저장하면 탭과 목록 필터를 유지하며 목록 상태를 갱신한다', async () => {
  show('/admin/complexes/7?provider=LH')
  await screen.findByLabelText('이름')
  fireEvent.click(screen.getByRole('button', { name: '데이터 검증' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  expect(screen.queryByLabelText('이름')).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '공식 자료 확인' } })
  const review: ComplexReview = { id: 1, outcome: 'VERIFIED', fields: ['NAME'], checkedValues: { NAME: summary.name },
    evidenceNote: '공식 자료 확인', evidenceUrl: null, actor: '관리자', reviewedAt: '2026-10-09T00:00:00Z' }
  const saved: ComplexVerification = { ...evidence(), status: 'VERIFIED', latestReview: review, history: [review] }
  const reviewed: ManagementSummary = { ...summary, complex: { ...summary.complex!, verificationStatus: 'VERIFIED', reviewedFieldCount: 1 } }
  verification.saveComplexReview.mockResolvedValue(saved)
  verification.getComplexVerification.mockResolvedValue(saved)
  api.getManagementDetail.mockResolvedValue(detail(reviewed))
  api.getManagementPage.mockResolvedValue(page(reviewed))
  fireEvent.click(screen.getByRole('button', { name: '검토 저장' }))
  await waitFor(() => expect(api.getManagementPage).toHaveBeenCalledTimes(2))
  expect(api.getManagementPage.mock.lastCall?.[1].get('provider')).toBe('LH')
  expect(screen.getByRole('button', { name: '데이터 검증' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('link', { name: summary.name }).closest('th')).toHaveTextContent('확인 완료 · 1/6항목')
  const panel = await screen.findByRole('region', { name: '단지 데이터 검증' })
  expect(within(panel).getByText(/1\/6항목/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '기본정보' }))
  expect(screen.getByLabelText('이름')).toHaveValue(summary.name)
  fireEvent.click(screen.getByRole('button', { name: '주택형·연결 공고' }))
  expect(screen.getByText('등록된 주택형이 없습니다.')).toBeVisible()
})

it('검토 작성 중 탭·필터·편집 닫기 이동을 취소하면 입력과 선택을 보존한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  show()
  await screen.findByLabelText('이름')
  expect(screen.getByLabelText('검토 상태')).toHaveValue('UNREVIEWED')
  expect(api.getManagementPage.mock.lastCall?.[1].get('verification')).toBe('UNREVIEWED')
  fireEvent.click(screen.getByRole('button', { name: '데이터 검증' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '작성 중인 근거' } })
  fireEvent.click(screen.getByRole('button', { name: '기본정보' }))
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  fireEvent.submit(screen.getByRole('button', { name: '검색' }).closest('form')!)
  expect(confirm).toHaveBeenCalledTimes(3)
  expect(screen.getByLabelText('확인 근거·메모')).toHaveValue('작성 중인 근거')
  expect(screen.getByRole('checkbox', { name: '단지명 확인' })).toBeChecked()
  expect(api.getManagementPage).toHaveBeenCalledOnce()
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('link', { name: '편집 닫기' }))
  expect(screen.queryByRole('region', { name: '단지 상세·수정' })).not.toBeInTheDocument()
  expect(screen.getByLabelText('검토 상태')).toHaveValue('UNREVIEWED')
})

it('검토 저장 중에는 편집 탭과 삭제를 잠그고 완료 후 해제한다', async () => {
  let finish!: (value: ComplexVerification) => void
  verification.saveComplexReview.mockReturnValue(new Promise<ComplexVerification>(resolve => { finish = resolve }))
  show()
  await screen.findByLabelText('이름')
  fireEvent.click(screen.getByRole('button', { name: '데이터 검증' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: '단지명 확인' }))
  fireEvent.change(screen.getByLabelText('확인 근거·메모'), { target: { value: '공식 자료 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '검토 저장' }))
  expect(screen.getByRole('button', { name: '기본정보' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '삭제' })).toBeDisabled()
  await act(async () => finish(evidence()))
  await waitFor(() => expect(screen.getByRole('button', { name: '기본정보' })).toBeEnabled())
  expect(screen.getByRole('button', { name: '삭제' })).toBeEnabled()
})
